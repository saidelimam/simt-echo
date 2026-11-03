# Spend governance (R9-05, review 0009 F4)

The per-job SAM governor (`worker/src/samLens.ts`) caps one job at
`budget.maxSamFrames` (hard ceiling 240 frames, `types.ts`). The daily
cap in `worker/src/spend.ts` bounds the **sum of jobs** the deployment
accepts per UTC day, and the kill switch halts job creation entirely.

## Mechanism

Each job create reserves the job's worst case **before any job file
lands**:

- v1 multipart create (`POST /v1/jobs`): reserves `MAX_SAM_FRAMES` (240) —
  v1 carries no budget.
- v1.1 create (JSON body): reserves `budget.maxSamFrames` when declared,
  else 240. Idempotent replays do not re-reserve.

The reservation increments a per-UTC-day ledger object in the `MEDIA`
R2 bucket, guarded by etag-conditional put:

```
spend/daily/<YYYY-MM-DD>.json   {"reserved": <frames reserved today>}
```

Concurrent creates on the same day retry against the fresh value (≤8
attempts); a storm fails closed with `500 job_create_failed` and writes
nothing. A reserve is a cap, not a bill — the governor bounds the SAM
calls a job actually makes, and a job that fails after reserving simply
spends toward the cap. The ledger is swept by a 2-day R2 lifecycle rule
(`expire-spend`, `wrangler.jsonc`); it is audit residue, not state.

## Configuration (dashboard vars)

| Var                   | Effect                                                                                                   | Default |
| --------------------- | -------------------------------------------------------------------------------------------------------- | ------- |
| `DAILY_MAX_SAM_FRAMES`| Daily SAM-frame cap. Unset or unparseable → 1200 (five jobs at the 240 ceiling).                          | `1200`  |
| `SPEND_KILL_SWITCH`   | Exactly `on` → every job create (v1 and v1.1, including idempotent replays) is rejected `503 spend_disabled` before the ledger is read. Any other value is off. | unset (off) |

## Failure modes

| Symptom                                        | Meaning                                                        | Action                                            |
| ---------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------- |
| `503 spend_disabled` on job create             | Kill switch is `on`.                                           | Expected while set; clear the var to resume.      |
| `503 daily_budget_exceeded` on job create      | Today's ledger + this job's reserve exceeds the cap.           | Normal at the cap; raise `DAILY_MAX_SAM_FRAMES` to allow more. Resets at UTC midnight. |
| `500 job_create_failed` on job create          | Etag contention storm (or R2 storage failure) — fail closed, nothing written. | Rare; a burst of concurrent creates on one day. Client retries. |

## Inspecting the ledger

```bash
npx wrangler r2 object get echo-media spend/daily/$(date -u +%F).json
```

`reserved` is today's cumulative reservation in SAM frames. The object
disappears two days after the day it covered.

## Alerts worth setting

- `503 daily_budget_exceeded` responses trending nonzero: a client is
  hitting the daily cap (or the cap is too low for the user base).
- `500 job_create_failed` spikes: contention storm — check create-rate
  bursts; sustained recurrence means `MAX_ATTEMPTS` is too small for the
  load.
