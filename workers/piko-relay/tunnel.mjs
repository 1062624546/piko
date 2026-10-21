export const MAGIC = new TextEncoder().encode("PIKO2\n");
const MAX = 65536,
  encoder = new TextEncoder(),
  decoder = new TextDecoder("utf-8", { fatal: true });
export const text = (a) => decoder.decode(a);
export const bytes = (a) => encoder.encode(a);
export const concat = (...items) => {
  const out = new Uint8Array(items.reduce((n, a) => n + a.length, 0));
  let at = 0;
  for (const a of items) {
    out.set(a, at);
    at += a.length;
  }
  return out;
};
export const hex = (a) =>
  Array.from(a, (x) => x.toString(16).padStart(2, "0")).join("");
export async function keyId(token) {
  return new Uint8Array(
    await crypto.subtle.digest("SHA-256", bytes(token)),
  ).slice(0, 16);
}
export async function key(token, salt, direction) {
  const material = await crypto.subtle.importKey(
    "raw",
    bytes(token),
    "HKDF",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    {
      name: "HKDF",
      hash: "SHA-256",
      salt,
      info: bytes("piko-tunnel-v2/" + direction),
    },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}
function parameters(sequence, size) {
  if (sequence > 0xffffffff) throw new Error("Stream too long");
  const iv = new Uint8Array(12);
  new DataView(iv.buffer).setBigUint64(4, BigInt(sequence));
  const aad = concat(MAGIC, new Uint8Array(12)),
    view = new DataView(aad.buffer);
  view.setBigUint64(MAGIC.length, BigInt(sequence));
  view.setUint32(MAGIC.length + 8, size);
  return { name: "AES-GCM", iv, additionalData: aad, tagLength: 128 };
}
export class Reader {
  constructor(stream) {
    if (!stream) throw new Error("Missing body");
    this.reader = stream.getReader();
    this.pending = new Uint8Array();
  }
  async read(size) {
    const output = new Uint8Array(size);
    let at = 0;
    while (at < size) {
      if (!this.pending.length) {
        const next = await this.reader.read();
        if (next.done) throw new Error("Truncated tunnel");
        this.pending = next.value;
      }
      const count = Math.min(size - at, this.pending.length);
      output.set(this.pending.subarray(0, count), at);
      this.pending = this.pending.subarray(count);
      at += count;
    }
    return output;
  }
  async cancel(reason) {
    await this.reader.cancel(reason);
  }
}
export class Records {
  constructor(reader, key) {
    this.reader = reader;
    this.key = key;
    this.sequence = 0;
    this.ended = false;
  }
  async read() {
    if (this.ended) throw new Error("Stream ended");
    const size = new DataView((await this.reader.read(4)).buffer).getUint32(0);
    if (size < 17 || size > MAX + 17) throw new Error("Invalid frame length");
    const plain = new Uint8Array(
      await crypto.subtle.decrypt(
        parameters(this.sequence++, size),
        this.key,
        await this.reader.read(size),
      ),
    );
    const type = plain[0];
    if (type > 2) throw new Error("Invalid frame type");
    if (type === 2) this.ended = true;
    return { type, data: plain.subarray(1) };
  }
  body() {
    return new ReadableStream({
      pull: async (c) => {
        try {
          const { type, data } = await this.read();
          if (type === 0) throw new Error("Repeated metadata");
          if (data.length) c.enqueue(data);
          if (this.ended) c.close();
        } catch (e) {
          c.error(e);
          await this.reader.cancel(e).catch(() => {});
        }
      },
      cancel: (r) => this.reader.cancel(r),
    });
  }
}
export class Writer {
  constructor(key) {
    this.key = key;
    this.sequence = 0;
  }
  async frame(type, data) {
    if (data.length > MAX) throw new Error("Frame too large");
    const size = data.length + 17,
      prefix = new Uint8Array(4);
    new DataView(prefix.buffer).setUint32(0, size);
    return concat(
      prefix,
      new Uint8Array(
        await crypto.subtle.encrypt(
          parameters(this.sequence++, size),
          this.key,
          concat(new Uint8Array([type]), data),
        ),
      ),
    );
  }
  stream(metadata, body, prefix) {
    let first = true,
      pending = new Uint8Array(),
      ended = false;
    const reader = body?.getReader();
    return new ReadableStream({
      pull: async (c) => {
        try {
          if (first) {
            first = false;
            const frame = await this.frame(0, bytes(JSON.stringify(metadata)));
            c.enqueue(prefix ? concat(prefix, frame) : frame);
            return;
          }
          while (pending.length <= MAX && !ended) {
            const next = reader ? await reader.read() : { done: true };
            if (next.done) ended = true;
            else pending = concat(pending, next.value);
          }
          const count = Math.min(MAX, pending.length),
            final = ended && pending.length <= MAX;
          c.enqueue(
            await this.frame(final ? 2 : 1, pending.subarray(0, count)),
          );
          pending = pending.subarray(count);
          if (final) c.close();
        } catch (e) {
          c.error(e);
          await reader?.cancel(e).catch(() => {});
        }
      },
      cancel: (r) => reader?.cancel(r),
    });
  }
}
export async function tunnel(request, env, ctx, forward) {
  if (request.method !== "POST" || new URL(request.url).search)
    return new Response("Tunnel unavailable", { status: 400 });
  const reader = new Reader(request.body);
  let responseKey;
  const responseSalt = crypto.getRandomValues(new Uint8Array(32));
  const encrypted = (meta, body) =>
    new Writer(responseKey).stream(meta, body, responseSalt);
  try {
    const prefix = await reader.read(MAGIC.length + 48);
    if (hex(prefix.subarray(0, MAGIC.length)) !== hex(MAGIC))
      throw new Error("Invalid protocol");
    const id = prefix.subarray(MAGIC.length, MAGIC.length + 16),
      salt = prefix.subarray(MAGIC.length + 16);
    const tokens = Object.values(JSON.parse(env.RELAY_TOKENS || "{}"));
    if (tokens.length > 100) throw new Error("Invalid configuration");
    let token;
    for (const candidate of tokens) {
      if (
        typeof candidate !== "string" ||
        !/^[A-Za-z0-9_-]{24,256}$/.test(candidate)
      )
        continue;
      const expected = await keyId(candidate);
      let diff = 0;
      for (let i = 0; i < 16; i++) diff |= expected[i] ^ id[i];
      if (!diff) token = candidate;
    }
    if (!token) throw new Error("Unavailable");
    const records = new Records(reader, await key(token, salt, "request"));
    const metadata = await records.read();
    if (metadata.type !== 0) throw new Error("Missing metadata");
    const meta = JSON.parse(text(metadata.data));
    if (
      !Number.isSafeInteger(meta.timestamp) ||
      Math.abs(Date.now() - meta.timestamp) > 120000
    )
      throw new Error("Expired request");
    responseKey = await key(token, concat(salt, responseSalt), "response");
    const accepted = await env.REPLAY_GUARD.getByName(hex(id)).claim(
      hex(salt),
      Date.now() + 300000,
    );
    if (!accepted) throw new Error("Replayed request");
    if (
      typeof meta.url !== "string" ||
      meta.url.length > 16384 ||
      !Array.isArray(meta.headers) ||
      meta.headers.length > 128
    )
      throw new Error("Invalid metadata");
    const headers = new Headers(meta.headers);
    headers.delete("Content-Length");
    headers.delete("Transfer-Encoding");
    headers.delete("X-Piko-Relay-Token");
    headers.set("Accept-Encoding", "identity");
    if (meta.contentType) headers.set("Content-Type", meta.contentType);
    headers.set("X-Piko-Relay-Token", token);
    const hasBody =
      !["GET", "HEAD"].includes(meta.method) && meta.bodyLength !== 0;
    let body;
    if (hasBody) {
      body = records.body();
      if (Number.isSafeInteger(meta.bodyLength) && meta.bodyLength >= 0)
        headers.set("Content-Length", String(meta.bodyLength));
    } else {
      const end = await records.read();
      if (end.type !== 2 || end.data.length) throw new Error("Unexpected body");
      await reader.cancel().catch(() => {});
    }
    const internal = new URL("/v1/relay", request.url);
    internal.searchParams.set("url", meta.url);
    const upstream = await forward(
      new Request(internal, {
        method: meta.method,
        headers,
        body,
        duplex: "half",
      }),
      env,
      ctx,
    );
    if (upstream.headers.has("X-Piko-Relay-Error")) {
      await upstream.body?.cancel();
      throw new Error("Upstream relay failed");
    }
    const responseHeaders = Array.from(upstream.headers).filter(
      ([n]) => n !== "x-piko-relay-protocol",
    );
    return new Response(
      encrypted(
        { status: upstream.status, headers: responseHeaders },
        upstream.body,
      ),
      {
        headers: {
          "Content-Type": "application/octet-stream",
          "Cache-Control": "no-store",
          "X-Piko-Tunnel": "2",
        },
      },
    );
  } catch {
    await reader.cancel().catch(() => {});
    if (responseKey)
      return new Response(
        encrypted({ error: "请求被拒绝、已过期或上游不可达" }, null),
        {
          headers: {
            "Content-Type": "application/octet-stream",
            "Cache-Control": "no-store",
            "X-Piko-Tunnel": "2",
          },
        },
      );
    return new Response("Tunnel unavailable", {
      status: 503,
      headers: { "Cache-Control": "no-store" },
    });
  }
}
