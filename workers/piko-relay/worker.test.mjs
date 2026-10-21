import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { validateTarget } from './worker.mjs';
const token = 'a'.repeat(48);
const env = { RELAY_TOKENS: JSON.stringify({ owner: token }) };
const request = (target, options = {}) => new Request(`https://relay.example/v1/relay?url=${encodeURIComponent(target)}`,
  { headers: { 'X-Piko-Relay-Token': token, Authorization: 'Bearer pikpak-token', 'X-Global-File-Token': 'archive-token', Range: 'bytes=10-19' }, ...options });

test('rejects unrelated hosts, credentials, private addresses, suffix tricks and media writes', () => {
  for (const url of ['https://127.0.0.1/', 'https://[::1]/', 'http://user.mypikpak.com/',
    'https://mypikpak.com.evil.test/', 'https://evil-mypikpak.com/', 'https://user.mypikpak.com:444/',
    'https://token@user.mypikpak.com/', 'https://example.com/'])
    assert.throws(() => validateTarget(url, 'GET'));
  assert.throws(() => validateTarget('https://dl.mypikpak.com/file', 'POST'));
  assert.equal(validateTarget('https://api-drive.mypikpak.net/drive/v1/files', 'POST').host, 'api-drive.mypikpak.net');
});
test('authentication fails closed, relay auth failure does not expire PikPak session', async () => {
  assert.equal((await worker.fetch(new Request('https://relay.example/v1/check'), env)).status, 401);
  assert.equal((await worker.fetch(new Request('https://relay.example/v1/relay?url=x'), env)).status, 503);
  assert.equal((await worker.fetch(request('https://user.mypikpak.com/'), {})).status, 503);
});
test('streaming response preserves Range and upstream Authorization, strips relay credential and cache cookies', async () => {
  const previous = globalThis.fetch;
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), 'https://dl.mypikpak.com/file?sign=abc');
    assert.equal(init.headers.get('X-Piko-Relay-Token'), null);
    assert.equal(init.headers.get('Authorization'), 'Bearer pikpak-token');
    assert.equal(init.headers.get('Range'), 'bytes=10-19');
    assert.equal(init.headers.get('X-Global-File-Token'), 'archive-token');
    return new Response('0123456789', { status: 206, headers: { 'Content-Range': 'bytes 10-19/20', 'Set-Cookie': 'bad=1' } });
  };
  try {
    const result = await worker.fetch(request('https://dl.mypikpak.com/file?sign=abc'), env);
    assert.equal(result.status, 206); assert.equal(result.headers.get('Content-Range'), 'bytes 10-19/20');
    assert.equal(result.headers.get('Set-Cookie'), null); assert.equal(await result.text(), '0123456789');
  } finally { globalThis.fetch = previous; }
});
test('blocks redirect escape and never returns a redirect carrying the relay token', async () => {
  const previous = globalThis.fetch;
  let calls = 0;
  globalThis.fetch = async () => { calls++; return new Response(null, { status: 302, headers: { Location: 'https://evil.test/steal' } }); };
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
