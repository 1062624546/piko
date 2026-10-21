import { tunnel } from "./tunnel.mjs";
import worker from "./worker.mjs";
import { DurableObject } from "cloudflare:workers";
/** Only random salts and expiry are stored, never business data. */
export class PikoReplayGuard extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec(
      "CREATE TABLE IF NOT EXISTS seen (nonce TEXT PRIMARY KEY, expires INTEGER NOT NULL)",
    );
  }
  async fetch(request) {
    const env = {
      RELAY_TOKENS: this.env.RELAY_TOKENS,
      REPLAY_GUARD: {
        getByName: () => ({
          claim: (nonce, expires) => this.claim(nonce, expires),
        }),
      },
    };
    return tunnel(request, env, this.ctx, (r, e, c) => worker.fetch(r, e, c));
  }
  async claim(nonce, expires) {
    if (
      !/^[a-f0-9]{64}$/.test(nonce) ||
      expires < Date.now() ||
      expires > Date.now() + 310000
    )
      return false;
    const sql = this.ctx.storage.sql;
    sql.exec("DELETE FROM seen WHERE expires <= ?", Date.now());
    const inserted = sql
      .exec(
        "INSERT INTO seen(nonce,expires) VALUES (?,?) ON CONFLICT DO NOTHING RETURNING nonce",
        nonce,
        expires,
      )
      .toArray();
    if (!inserted.length) return false;
    if (sql.exec("SELECT COUNT(*) AS count FROM seen").one().count === 1)
      await this.ctx.storage.setAlarm(expires);
    return true;
  }
  async alarm() {
    this.ctx.storage.sql.exec(
      "DELETE FROM seen WHERE expires <= ?",
      Date.now(),
    );
    const next = this.ctx.storage.sql
      .exec("SELECT MIN(expires) AS expires FROM seen")
      .one().expires;
    if (next !== null) await this.ctx.storage.setAlarm(next);
  }
}
