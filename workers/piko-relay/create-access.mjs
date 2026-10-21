import { randomBytes } from 'node:crypto';
import { writeFileSync } from 'node:fs';

const raw = process.argv[2];
if (!raw) throw new Error('Usage: node create-access.mjs https://your-piko-relay.workers.dev [name]');
const endpoint = new URL(raw);
if (endpoint.protocol !== 'https:' || endpoint.port || endpoint.username || endpoint.password ||
    endpoint.pathname !== '/' || endpoint.search || endpoint.hash) throw new Error('Use an HTTPS root URL');
const token = randomBytes(24).toString('hex');
const name = process.argv[3] || '我的 Piko 中继';
const link = new URL('piko://proxy');
link.searchParams.set('url', endpoint.origin);
link.searchParams.set('token', token);
link.searchParams.set('name', name);
writeFileSync('relay-secrets.json', JSON.stringify({ RELAY_TOKENS: JSON.stringify({ owner: token }) }, null, 2), { mode: 0o600, flag: 'wx' });
writeFileSync('relay-access.txt', `${link.href}\n`, { mode: 0o600, flag: 'wx' });
console.log('已生成 relay-secrets.json 和 relay-access.txt。它们包含访问凭证，请勿提交到 Git。');
