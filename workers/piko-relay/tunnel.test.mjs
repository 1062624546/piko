import test from "node:test";
import assert from "node:assert/strict";
import {
  MAGIC,
  bytes,
  concat,
  hex,
  key,
  keyId,
  Reader,
  Records,
  Writer,
  text,
  tunnel,
} from "./tunnel.mjs";
const token = "a".repeat(48);
const stream = (a) =>
  new ReadableStream({
    start(c) {
      c.enqueue(a);
      c.close();
    },
  });
const collect = async (s) =>
  new Uint8Array(await new Response(s).arrayBuffer());
const claims = new Set();
const env = {
  RELAY_TOKENS: JSON.stringify({ owner: token }),
  REPLAY_GUARD: {
    getByName: () => ({
      claim: async (nonce) => {
        if (claims.has(nonce)) return false;
        claims.add(nonce);
        return true;
      },
    }),
  },
};
async function packet(body = new Uint8Array(), extra = {}) {
  const salt = crypto.getRandomValues(new Uint8Array(32));
  const meta = {
    url: "https://api.example.com/upload?private=secret",
    method: "POST",
    timestamp: Date.now(),
    headers: [
      ["Authorization", "Bearer private"],
      ["Range", "bytes=0-31"],
    ],
    bodyLength: body.length,
    ...extra,
  };
  const encoded = await collect(
    new Writer(await key(token, salt, "request")).stream(meta, stream(body)),
  );
  return { salt, encoded: concat(MAGIC, await keyId(token), salt, encoded) };
}
const request = (p) =>
  new Request("https://relay.example.com/v2/tunnel", {
    method: "POST",
    body: p.encoded,
    duplex: "half",
  });
async function decode(response, salt) {
  const r = new Reader(response.body),
    rs = await r.read(32),
    records = new Records(r, await key(token, concat(salt, rs), "response"));
  const meta = JSON.parse(text((await records.read()).data));
  const body = await collect(records.body());
  return { meta, body };
}
test("encrypted metadata and 200 KiB upload/response round trip; no plaintext business fields", async () => {
  const body = crypto.getRandomValues(new Uint8Array(60000)),
    large = concat(body, body, body, body),
    p = await packet(large);
  const wire = Buffer.from(p.encoded);
  for (const value of [
    "api.example.com",
    "private=secret",
    "Bearer private",
    token,
  ])
    assert.equal(wire.includes(Buffer.from(value)), false);
  const response = await tunnel(request(p), env, {}, async (req) => {
    assert.equal(
      new URL(req.url).searchParams.get("url"),
      "https://api.example.com/upload?private=secret",
    );
    assert.equal(req.headers.get("Authorization"), "Bearer private");
    assert.deepEqual(new Uint8Array(await req.arrayBuffer()), large);
    return new Response(large, {
      status: 206,
      headers: {
        "Content-Range": "bytes 0-239999/240000",
        "X-Piko-Upstream-Url": "https://cdn.example.com/private",
      },
    });
  });
  assert.equal(response.headers.get("Content-Range"), null);
  const decoded = await decode(response, p.salt);
  assert.equal(decoded.meta.status, 206);
  assert.deepEqual(decoded.body, large);
});
test("replays never reach upstream; response salt differs even for the same request", async () => {
  const p = await packet();
  let count = 0;
  const forward = async () => {
    count++;
    return new Response("ok");
  };
  const a = await tunnel(request(p), env, {}, forward),
    b = await tunnel(request(p), env, {}, forward);
  const aa = await collect(a.body),
    bb = await collect(b.body);
  assert.notDeepEqual(aa.slice(0, 32), bb.slice(0, 32));
  assert.equal(count, 1);
  const result = await decode(new Response(bb), p.salt);
  assert.ok(result.meta.error);
});
test("wrong token, metadata tampering and stale timestamp fail before upstream", async () => {
  for (const mode of ["tamper", "wrong", "stale"]) {
    const p = await packet(
      new Uint8Array(),
      mode === "stale" ? { timestamp: Date.now() - 300000 } : {},
    );
    if (mode === "tamper") p.encoded[80] ^= 1;
    if (mode === "wrong") p.encoded[6] ^= 1;
    const r = await tunnel(request(p), env, {}, () => {
      throw new Error("must not forward");
    });
    assert.equal(r.status, 503);
  }
});
test("truncated, reordered, modified and wrong-direction records fail authentication", async () => {
  const salt = new Uint8Array(32),
    k = await key(token, salt, "request"),
    w = new Writer(k);
  const a = await w.frame(1, bytes("first")),
    b = await w.frame(2, bytes("last"));
  for (const wire of [
    a.slice(0, -1),
    concat(b, a),
    (() => {
      const c = a.slice();
      c[10] ^= 1;
      return c;
    })(),
  ]) {
    await assert.rejects(() => new Records(new Reader(stream(wire)), k).read());
  }
  const wrong = new Records(
    new Reader(stream(a)),
    await key(token, salt, "response"),
  );
  await assert.rejects(() => wrong.read());
});
test("missing final record is rejected after authenticated data", async () => {
  const k = await key(token, new Uint8Array(32), "request"),
    w = new Writer(k),
    a = await w.frame(1, bytes("partial"));
  const r = new Records(new Reader(stream(a)), k);
  await r.read();
  await assert.rejects(() => r.read());
});
test("fixed cross-language HKDF and GCM vector", async () => {
  const k = await key(
    token,
    new Uint8Array(32).map((_, i) => i),
    "request",
  );
  const wire = await new Writer(k).frame(0, bytes('{"test":"互通"}'));
  assert.equal(
    hex(wire),
    "000000228cc4442a1db5f25b0efc928d3869b3f18e56af169ef1feee5c589a42f5e46f781546",
  );
});
