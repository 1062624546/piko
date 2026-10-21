import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

test("SQLite Durable Object atomically accepts one concurrent claim across repeated RPC calls", async () => {
  const mf = new Miniflare(
    convertV4MiniflareOptions({
      workers: [
        {
          name: "replay-test",
          modules: [
            {
              type: "ESModule",
              path: "test.mjs",
              contents: `export {PikoReplayGuard} from './replay.mjs'; export default {async fetch(request,env){ const nonce=new URL(request.url).searchParams.get('nonce');const accepted=await env.REPLAY_GUARD.getByName('test-owner').claim(nonce,Date.now()+300000);return Response.json({accepted});}};`,
            },
            {
              type: "ESModule",
              path: "replay.mjs",
              contents: await readFile(
                new URL("./replay.mjs", import.meta.url),
                "utf8",
              ),
            },
          ],
          compatibilityDate: "2026-10-21",
          durableObjects: {
            REPLAY_GUARD: { className: "PikoReplayGuard", useSQLite: true },
          },
        },
      ],
    }),
  );
  try {
    const url = "https://test.invalid/?nonce=" + "a".repeat(64);
    const responses = await Promise.all(
      Array.from({ length: 8 }, () =>
        mf.dispatchFetch(url).then((r) => r.json()),
      ),
    );
    assert.equal(responses.filter((x) => x.accepted).length, 1);
    assert.equal(
      (await mf.dispatchFetch(url).then((r) => r.json())).accepted,
      false,
    );
  } finally {
    await mf.dispose();
  }
});
