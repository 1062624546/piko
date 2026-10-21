const ROOTS = ['mypikpak.com', 'mypikpak.net', 'pikpak.me', 'pikpakdrive.com'];
const METHODS = new Set(['GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE']);
const PROTOCOL = 'piko-relay-v1';
const API_HOSTS = new Set(ROOTS.flatMap(root => ['user', 'api-drive', 'api-dav'].map(sub => `${sub}.${root}`)));
const REQUEST_HEADERS = new Set(['accept', 'accept-language', 'authorization', 'content-type', 'content-encoding',
  'user-agent', 'x-device-id', 'x-captcha-token', 'x-global-file-token', 'x-client-id', 'x-client-version', 'x-peer-id',
  'range', 'if-range', 'if-none-match', 'if-modified-since']);
const RESPONSE_HEADERS = new Set(['content-type', 'content-length', 'content-encoding', 'content-range', 'accept-ranges',
  'etag', 'last-modified', 'retry-after', 'www-authenticate', 'x-request-id']);

function reply(status, message) {
  return Response.json({ protocol: PROTOCOL, message }, { status,
    headers: { 'X-Piko-Relay-Protocol': PROTOCOL, 'Cache-Control': 'no-store' } });
}

export function validateTarget(raw, method) {
  let url;
  try { url = new URL(raw); } catch { throw new Error('Invalid target'); }
  if (url.protocol !== 'https:' || url.port || url.username || url.password || url.hash)
    throw new Error('HTTPS targets on port 443 only');
  const api = API_HOSTS.has(url.hostname);
  const media = ROOTS.some(root => url.hostname === root || url.hostname.endsWith(`.${root}`));
  if (!api && (!media || !['GET', 'HEAD'].includes(method))) throw new Error('Target not allowed');
  return url;
}

async function authorized(request, env) {
  const supplied = request.headers.get('X-Piko-Relay-Token') || '';
  if (!/^[A-Za-z0-9_-]{24,256}$/.test(supplied)) return false;
  // JSON 对象的键是凭证名称，便于吊销单个用户或轮换；值是随机 token。
  let tokens;
  try { tokens = Object.values(JSON.parse(env.RELAY_TOKENS || '{}')); } catch { return false; }
  if (!tokens.length || tokens.length > 100) return false;
  const encode = value => new TextEncoder().encode(value);
  const hash = value => crypto.subtle.digest('SHA-256', encode(value));
  const input = new Uint8Array(await hash(supplied));
  let matched = 0;
  for (const token of tokens) {
    if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{24,256}$/.test(token)) continue;
    const expected = new Uint8Array(await hash(token));
    let difference = 0;
    for (let i = 0; i < 32; i++) difference |= input[i] ^ expected[i];
    matched |= Number(difference === 0);
  }
  return matched !== 0;
}

function filteredHeaders(source, allowed) {
  const result = new Headers();
  for (const [key, value] of source) if (allowed.has(key.toLowerCase())) result.set(key, value);
  return result;
}

async function fetchWithHeaderTimeout(url, init, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try { return await fetch(url, { ...init, signal: controller.signal }); }
  finally { clearTimeout(timer); }
}

export default {
  async fetch(request, env) {
    const incoming = new URL(request.url);
    if (incoming.pathname === '/health' && ['GET', 'HEAD'].includes(request.method))
      return reply(200, 'Piko relay is running');
    if (!['/v1/check', '/v1/relay'].includes(incoming.pathname)) return reply(404, 'Not found');
    if (!await authorized(request, env)) {
      // API relay 故障不能伪装成 PikPak 的账号 401，避免客户端错误地注销账号。
      return reply(incoming.pathname === '/v1/check' ? 401 : 503, 'Relay credential invalid');
    }
    if (incoming.pathname === '/v1/check') {
      if (request.method !== 'GET') return reply(405, 'Method not allowed');
      try {
        const response = await fetchWithHeaderTimeout('https://api-drive.mypikpak.com/drive/v1/files?page_size=1', {
          redirect: 'manual', headers: { Accept: 'application/json' },
        }, 12_000);
        await response.body?.cancel();
        return reply(response.status === 401 ? 200 : 502,
          response.status === 401 ? 'Credential valid; PikPak reachable' : 'Unexpected upstream status');
      } catch { return reply(502, 'PikPak unreachable'); }
    }
    if (!METHODS.has(request.method)) return reply(405, 'Method not allowed');
    const values = incoming.searchParams.getAll('url');
    if (values.length !== 1 || values[0].length > 16384) return reply(400, 'One target URL is required');
    let target;
    try { target = validateTarget(values[0], request.method); }
    catch { return reply(403, 'Target not allowed'); }
    let headers = filteredHeaders(request.headers, REQUEST_HEADERS);
    try {
      // 大响应直接透传流，绝不把视频或下载文件读入内存。
      for (let redirects = 0; redirects <= 5; redirects++) {
        const response = await fetchWithHeaderTimeout(target, {
          method: request.method, headers, redirect: 'manual',
          body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
        }, 60_000);
        if ([301, 302, 303, 307, 308].includes(response.status)) {
          const location = response.headers.get('Location');
          await response.body?.cancel();
          // 不重放写操作，避免一次请求变成两次写入；不把 Location 返回给 App。
          if (!location || !['GET', 'HEAD'].includes(request.method)) return reply(502, 'Upstream redirect refused');
          const next = validateTarget(new URL(location, target).href, request.method);
          if (next.origin !== target.origin) {
            headers = new Headers(headers);
            for (const key of ['authorization', 'x-captcha-token', 'x-device-id', 'x-global-file-token']) headers.delete(key);
          }
          target = next;
          continue;
        }
        const outgoing = filteredHeaders(response.headers, RESPONSE_HEADERS);
        outgoing.set('Cache-Control', 'no-store');
        outgoing.set('X-Piko-Relay-Protocol', PROTOCOL);
        return new Response(response.body, { status: response.status, headers: outgoing });
      }
      return reply(502, 'Too many upstream redirects');
    } catch { return reply(502, 'Upstream request failed'); }
  },
};
