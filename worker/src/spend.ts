// R10-05 (review 0010, F4): the account-level daily spend cap. The lens
// governor (samLens.ts) caps ONE job at budget.maxSamFrames; nothing
// bounded the sum of jobs, and the per-token rate limit is the only
// throttle (F1 shared the token across installs).
//
// A per-UTC-day ledger object in the same R2 bucket as the media:
//
//   spend/daily/<YYYY-MM-DD>.json  {"reserved": <frames reserved today>}
//
// Each job create reserves the job's worst case (its declared budget,
// MAX_SAM_FRAMES when undeclared) before any job file lands. The
// read-modify-write is guarded by etag-conditional put: R2 rejects a put
// whose etag no longer matches, so concurrent creates on the same day
// retry against the fresh value, and a contention storm fails closed
// (job_create_failed, nothing written) rather than double-booking the
// day. The reserve is a cap, not a bill — the governor is what bounds the
// SAM calls a job actually makes; a reserve with no job is spent toward
// the cap, never under it.
//
// SPEND_KILL_SWITCH=on rejects every create (503 spend_disabled) without
// touching the ledger. Ops: docs/operations/spend.md.

import type { Env } from "./index";

/** Default daily cap: five jobs per day at the 240-frame ceiling. */
export const DEFAULT_DAILY_MAX_SAM_FRAMES = 1200;

const LEDGER_PREFIX = "spend/daily/";
/** Attempts per reserve; a storm past this fails closed. */
const MAX_ATTEMPTS = 8;

export type ReserveOutcome = { ok: true } | { ok: false; reason: "over_cap" | "contention" };

/** The cap from `DAILY_MAX_SAM_FRAMES`; an absent or unparseable value
 * keeps the default — a typo in a var must not uncap the day. */
export function dailyMaxFrames(env: Pick<Env, "DAILY_MAX_SAM_FRAMES">): number {
  const raw = env.DAILY_MAX_SAM_FRAMES;
  if (raw === undefined || raw.trim() === "") return DEFAULT_DAILY_MAX_SAM_FRAMES;
  const n = Number.parseInt(raw, 10);
  return Number.isInteger(n) && n > 0 ? n : DEFAULT_DAILY_MAX_SAM_FRAMES;
}

/** The kill switch: exactly `on` halts every job create. Any other value
 * (or unset) is off, so a stray var cannot stop the product. */
export function spendDisabled(env: Pick<Env, "SPEND_KILL_SWITCH">): boolean {
  return env.SPEND_KILL_SWITCH === "on";
}

function utcDayKey(now: Date): string {
  // toISOString is UTC, so the day boundary is UTC midnight.
  return `${LEDGER_PREFIX}${now.toISOString().slice(0, 10)}.json`;
}

/** A ledger that is present but unreadable as `{reserved: n}` (wrong
 * shape, negative, fractional) reads as 0. A non-JSON object cannot reach
 * this — `obj.json()` throws and the caller treats it as 0 too. */
function reservedFrom(raw: unknown): number {
  const n = (raw as { reserved?: unknown } | null)?.reserved;
  return typeof n === "number" && Number.isInteger(n) && n >= 0 ? n : 0;
}

/**
 * Reserve `frames` against today's ledger. `ok` on success, `over_cap`
 * when the reservation would exceed the daily cap, `contention` when the
 * etag race is not resolved within MAX_ATTEMPTS (fail closed).
 */
export async function reserveDailySpend(
  env: Pick<Env, "MEDIA" | "DAILY_MAX_SAM_FRAMES">,
  frames: number,
): Promise<ReserveOutcome> {
  const key = utcDayKey(new Date());
  for (let attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
    const obj = await env.MEDIA.get(key);
    let reserved = 0;
    if (obj) {
      // A corrupt object (partial write, hand-edit) reads as 0: the day
      // restarts its budget rather than failing every create.
      try {
        reserved = reservedFrom(await obj.json());
      } catch {
        reserved = 0;
      }
    }
    if (reserved + frames > dailyMaxFrames(env)) return { ok: false, reason: "over_cap" };
    try {
      // Conditional on the etag we just read: a concurrent create landing
      // between get and put makes R2 reject this one, and the retry sees
      // the fresh total.
      await env.MEDIA.put(
        key,
        JSON.stringify({ reserved: reserved + frames }),
        obj ? { onlyIf: { etagMatches: obj.httpEtag } } : undefined,
      );
      return { ok: true };
    } catch {
      // Etag contention (or a storage failure): retry, then fail closed.
    }
  }
  return { ok: false, reason: "contention" };
}
