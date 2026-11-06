// R10-05 (review 0010, F4) test delta: the daily spend ledger — cap
// enforcement, etag-conditional concurrency, fail-closed storm, and the
// kill switch / cap-var parsing. The FakeR2 honors R2's etag semantics: a
// conditional put fails when the object's etag moved (or the object does
// not exist), so the ledger's read-modify-write races are exercised.

import { describe, expect, it } from "vitest";
import {
  DEFAULT_DAILY_MAX_SAM_FRAMES,
  dailyMaxFrames,
  reserveDailySpend,
  spendDisabled,
} from "./spend";

class LedgerR2 {
  store = new Map<string, { body: string; etag: string }>();
  conditionalPuts = 0;
  /** Fires inside put() before the etag check, so a test can land a
   * concurrent writer between the ledger's get and put. */
  onConditionalPut?: () => void;
  private seq = 0;

  async put(
    key: string,
    value: string,
    opts?: { onlyIf?: { etagMatches?: string } },
  ): Promise<null> {
    if (opts?.onlyIf?.etagMatches !== undefined) {
      this.conditionalPuts++;
      this.onConditionalPut?.();
      const entry = this.store.get(key);
      if (!entry || entry.etag !== opts.onlyIf.etagMatches) throw new Error("etag mismatch");
    }
    this.seq++;
    this.store.set(key, { body: value, etag: `e${this.seq}` });
    return null;
  }

  async get(key: string) {
    const entry = this.store.get(key);
    if (!entry) return null;
    return {
      // Production R2 exposes the tag two ways: `etag` is the bare value
      // `onlyIf.etagMatches` compares against; `httpEtag` is the quoted
      // HTTP entity tag, and a conditional put with the quoted form 412s.
      etag: entry.etag,
      httpEtag: `"${entry.etag}"`,
      json: async <T,>() => JSON.parse(entry.body) as T,
      text: async () => entry.body,
    };
  }

  seedLedger(reserved: number) {
    return this.put(`spend/daily/${today()}.json`, JSON.stringify({ reserved }));
  }

  ledger(): { reserved: number } {
    const entry = this.store.get(`spend/daily/${today()}.json`);
    return JSON.parse(entry ? entry.body : '{"reserved":0}');
  }
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function envFor(r2: LedgerR2, cap?: string) {
  return {
    MEDIA: r2 as never,
    ...(cap !== undefined ? { DAILY_MAX_SAM_FRAMES: cap } : {}),
  } as never;
}

describe("daily cap var and kill switch (R10-05)", () => {
  it("parses DAILY_MAX_SAM_FRAMES, defaulting on absent or unparseable", () => {
    expect(dailyMaxFrames({} as never)).toBe(DEFAULT_DAILY_MAX_SAM_FRAMES);
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "" } as never)).toBe(
      DEFAULT_DAILY_MAX_SAM_FRAMES,
    );
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "  " } as never)).toBe(
      DEFAULT_DAILY_MAX_SAM_FRAMES,
    );
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "240" } as never)).toBe(240);
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "0" } as never)).toBe(
      DEFAULT_DAILY_MAX_SAM_FRAMES,
    );
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "-5" } as never)).toBe(
      DEFAULT_DAILY_MAX_SAM_FRAMES,
    );
    expect(dailyMaxFrames({ DAILY_MAX_SAM_FRAMES: "abc" } as never)).toBe(
      DEFAULT_DAILY_MAX_SAM_FRAMES,
    );
  });

  it("treats exactly 'on' as the kill switch, everything else off", () => {
    expect(spendDisabled({ SPEND_KILL_SWITCH: "on" } as never)).toBe(true);
    expect(spendDisabled({ SPEND_KILL_SWITCH: "off" } as never)).toBe(false);
    expect(spendDisabled({ SPEND_KILL_SWITCH: "ON" } as never)).toBe(false);
    expect(spendDisabled({} as never)).toBe(false);
  });
});

describe("reserveDailySpend (R10-05)", () => {
  it("reserves under the cap and writes the UTC day's ledger object", async () => {
    const r2 = new LedgerR2();
    const out = await reserveDailySpend(envFor(r2, "1200"), 240);
    expect(out).toEqual({ ok: true });
    expect([...r2.store.keys()]).toEqual([`spend/daily/${today()}.json`]);
    expect(r2.ledger()).toEqual({ reserved: 240 });
  });

  it("reserves up to the exact cap, then refuses the next frame", async () => {
    const r2 = new LedgerR2();
    await r2.seedLedger(1150);
    expect(await reserveDailySpend(envFor(r2, "1200"), 50)).toEqual({ ok: true });
    expect(r2.ledger()).toEqual({ reserved: 1200 });
    expect(await reserveDailySpend(envFor(r2, "1200"), 1)).toEqual({
      ok: false,
      reason: "over_cap",
    });
    // A refusal leaves the ledger untouched.
    expect(r2.ledger()).toEqual({ reserved: 1200 });
  });

  it("refuses over the cap without writing", async () => {
    const r2 = new LedgerR2();
    await r2.seedLedger(1150);
    expect(await reserveDailySpend(envFor(r2, "1200"), 51)).toEqual({
      ok: false,
      reason: "over_cap",
    });
    expect(r2.ledger()).toEqual({ reserved: 1150 });
  });

  it("retries on etag contention and lands the fresh total", async () => {
    const r2 = new LedgerR2();
    await r2.seedLedger(1000);
    // A concurrent creator lands between our get and put on the first
    // attempt: our conditional put is rejected, and the retry reads its
    // reservation.
    let bumped = false;
    r2.onConditionalPut = () => {
      if (!bumped) {
        bumped = true;
        void r2.seedLedger(1100);
      }
    };
    expect(await reserveDailySpend(envFor(r2, "1200"), 50)).toEqual({ ok: true });
    expect(r2.conditionalPuts).toBe(2);
    expect(r2.ledger()).toEqual({ reserved: 1150 });
  });

  it("lands a second reserve of the same UTC day on the live ledger", async () => {
    // Regression: the conditional put compared `httpEtag` (the quoted HTTP
    // tag) against R2's unquoted etag, so every reserve after the first of
    // a day 412'd eight times and failed closed as contention — the 2026-11-06
    // incident where the first create of a UTC day succeeded and every
    // subsequent one returned job_create_failed.
    const r2 = new LedgerR2();
    expect(await reserveDailySpend(envFor(r2, "1200"), 120)).toEqual({ ok: true });
    expect(await reserveDailySpend(envFor(r2, "1200"), 120)).toEqual({ ok: true });
    expect(r2.ledger()).toEqual({ reserved: 240 });
  });

  it("fails closed on a persistent contention storm, nothing written", async () => {
    const r2 = new LedgerR2();
    await r2.seedLedger(100);
    // Every conditional put loses the race.
    r2.onConditionalPut = () => {
      void r2.seedLedger(101);
    };
    expect(await reserveDailySpend(envFor(r2, "1200"), 10)).toEqual({
      ok: false,
      reason: "contention",
    });
    // Eight attempts, then closed — the ledger holds only the storm's
    // writes, never ours.
    expect(r2.conditionalPuts).toBe(8);
    expect(r2.ledger()).toEqual({ reserved: 101 });
  });

  it("fails closed on a corrupt ledger instead of resetting the day", async () => {
    const r2 = new LedgerR2();
    await r2.put(`spend/daily/${today()}.json`, "{not json");
    expect(await reserveDailySpend(envFor(r2, "1200"), 240)).toEqual({
      ok: false,
      reason: "contention",
    });
    // No put was attempted: the corrupt object is neither read as 0 nor
    // overwritten, so the day's reservation cannot be spent twice.
    expect(r2.conditionalPuts).toBe(0);
    expect(await reserveDailySpend(envFor(r2, "1200"), 1)).toEqual({
      ok: false,
      reason: "contention",
    });
  });

  it("fails closed on a ledger of the wrong shape", async () => {
    const r2 = new LedgerR2();
    await r2.put(`spend/daily/${today()}.json`, JSON.stringify({ reserved: -5 }));
    expect(await reserveDailySpend(envFor(r2, "1200"), 10)).toEqual({
      ok: false,
      reason: "contention",
    });
    expect(r2.conditionalPuts).toBe(0);
  });
});
