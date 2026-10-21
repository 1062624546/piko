import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { validateTarget } from './worker.mjs';
const token = 'a'.repeat(48);
const env = { RELAY_TOKENS: JSON.stringify({ owner: token }) };
const request = (target, options = {}) => new Request(`https://relay.example/v1/relay?url=${encodeURIComponent(target)}`,
  { headers: { 'X-Piko-Relay-Token': token, Authorization: 'Bearer pikpak-token', 'X-Global-File-Token': 'archive-token', Range: 'bytes=10-19' }, ...options });

test('accepts external business hosts while blocking IPs, local names, credentials and non-HTTP', () => {
  for (const url of ['https://127.0.0.1/', 'https://[::1]/', 'https://10.0.0.1/', 'https://metadata.google.internal/',
    'https://localhost/', 'https://service.local/', 'https://service.lan/', 'https://api.example.com:444/',
    'https://token@user.mypikpak.com/', 'ftp://example.com/']) assert.throws(() => validateTarget(url, 'GET'));
  for (const url of ['https://api.github.com/repos/x/releases', 'https://objects.githubusercontent.com/file',
    'https://metatube.example.com/v1/search', 'https://bucket.oss.example.com/upload', 'http://api.example.com/status'])
    assert.equal(validateTarget(url, 'POST').href, url);
});
test('authentication fails closed, relay auth failure does not expire PikPak session', async () => {
  assert.equal((await worker.fetch(new Request('https://relay.example/v1/check'), env)).status, 401);
  assert.equal((await worker.fetch(new Request('https://relay.example/v1/relay?url=x'), env)).status, 503);
  assert.equal((await worker.fetch(request('https://user.mypikpak.com/'), {})).status, 503);
});
test('streaming response preserves Range and upstream headers while stripping relay credential', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), 'https://dl.mypikpak.com/file?sign=abc');
    assert.equal(init.headers.get('X-Piko-Relay-Token'), null);
    assert.equal(init.headers.get('Authorization'), 'Bearer pikpak-token');
    assert.equal(init.headers.get('Range'), 'bytes=10-19');
    assert.equal(init.headers.get('X-Global-File-Token'), 'archive-token');
    return new Response('0123456789', { status: 206, headers: { 'Content-Range': 'bytes 10-19/20', 'Set-Cookie': 'session=upstream' } });
  };
  try {
    const result = await worker.fetch(request('https://dl.mypikpak.com/file?sign=abc'), env);
    assert.equal(result.status, 206); assert.equal(result.headers.get('Content-Range'), 'bytes 10-19/20');
    assert.equal(result.headers.get('Set-Cookie'), 'session=upstream'); assert.equal(await result.text(), '0123456789');
  } finally { globalThis.fetch = previous; }
});
test('blocks redirect escape and never returns a redirect carrying the relay token', async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(null, { status: 302, headers: { Location: 'http://127.0.0.1/steal' } }); };
  try {
    const response = await worker.fetch(request('https://dl.mypikpak.com/file'), env);
    assert.equal(response.status, 502); assert.equal(calls, 1); assert.equal(response.headers.get('Location'), null);
  } finally { globalThis.fetch = previous; }
});
test('cross-origin media redirects strip PikPak sensitive headers', async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async (_, init) => {
    if (++calls === 1) return new Response(null, { status: 302, headers: { Location: 'https://dl2.mypikpak.net/file' } });
    assert.equal(init.headers.get('Authorization'), null);
    assert.equal(init.headers.get('X-Global-File-Token'), null);
    return new Response('media');
  };
  try { assert.equal(await (await worker.fetch(request('https://dl1.mypikpak.com/file'), env)).text(), 'media'); }
  finally { globalThis.fetch = previous; }
});
test('check verifies credential and upstream without forwarding login information', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (_, init) => { assert.equal(init.headers.Authorization, undefined); return new Response('{}', { status: 401 }); };
  try {
    const response = await worker.fetch(new Request('https://relay.example/v1/check', { headers: { 'X-Piko-Relay-Token': token } }), env);
    assert.equal(response.status, 200); assert.equal(response.headers.get('X-Piko-Relay-Protocol'), 'piko-relay-v1');
  } finally { globalThis.fetch = previous; }
});
test('upstream errors remain errors and write requests are not replayed after redirects', async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async (_, init) => { calls++; assert.equal(init.method, 'POST'); return new Response(null, { status: 307, headers: { Location: '/another' } }); };
  try {
    assert.equal((await worker.fetch(request('https://user.mypikpak.com/v1/auth/signin', { method: 'POST', body: '{}' }), env)).status, 502);
    assert.equal(calls, 1);
  } finally { globalThis.fetch = previous; }
});

test('upload headers and body are relayed, internal proxy headers are removed', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (_, init) => {
    assert.equal(init.headers.get('x-oss-security-token'), 'oss-token');
    assert.equal(init.headers.get('x-piko-relay-token'), null);
    assert.equal(init.headers.get('cf-connecting-ip'), null);
    assert.equal(await new Response(init.body).text(), 'upload-data');
    return new Response(null, {status: 200, headers: {ETag: 'uploaded'}});
  };
  try {
    const req = request('https://bucket.oss.example.com/object', {method:'PUT', body:'upload-data',
      headers:{'X-Piko-Relay-Token': token, 'x-oss-security-token':'oss-token', 'cf-connecting-ip':'127.0.0.1'}});
    const result = await worker.fetch(req, env);
    assert.equal(result.status,200); assert.equal(result.headers.get('etag'),'uploaded');
  } finally {globalThis.fetch=previous;}
});
test('redirects cannot downgrade HTTPS or recurse into the relay', async () => {
  const previous = globalThis.fetch;
  try {
    for (const target of ['http://cdn.example.com/file','https://relay.example/health']) {
      globalThis.fetch = async () => new Response(null,{status:302,headers:{Location:target}});
      assert.equal((await worker.fetch(request('https://cdn.example.com/file'),env)).status,502);
    }
  } finally {globalThis.fetch=previous;}
});
