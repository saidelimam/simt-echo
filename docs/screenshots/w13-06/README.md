# W13-06 — Processing "Constellation" — V-frame bindings

Device capture is owed: CI is headless, so no PNGs land here yet. A V
reviewer captures the frames below on a real build and drops them in this
directory next to this file.

Base: slice W13-06 commit (see the `W13-06` tracker row in
`docs/plans/0003-echo-luminous.md` for the SHA), mid-range phone,
dark + light, fontScale 1.0 and 2.0.

| # | Binding | What must read |
|---|---------|----------------|
| 1 | Working, all queued | Two dim dots on the route line, "Queued" under each, meter "0 / 120 frames", stage "Uploading…" |
| 2 | Working, scouting (FULL, mid-twinkle) | Sparkle glyph on the live checkpoint, "Scouting" label; twinkle is alpha-only — shape and label identical to STATIC |
| 3 | Working, grounded | Bright disc + mask-outline ring, "Grounded" label, node name |
| 4 | Working, skipped | Hollow ring, "Skipped" label |
| 5 | Budget meter, nonzero usage | "38 / 120 frames" (or the live figure) under the stage title |
| 6 | STATIC tier parity | Same job as 2–4 under STATIC: same shapes, labels, meter — nothing missing, nothing moving |
| 7 | Header exits | "Cancel" and "Continue without AI" both visible without scrolling on a 360 dp wide screen |

Honest-light checks on every frame: no determinate progress bar, no
percentage, no motion without a backing event; state is shape + label,
never hue alone.
