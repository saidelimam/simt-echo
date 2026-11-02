# DTW inventory — W15-04

Before extraction, a case-insensitive `dtw` content search of `shared/src`
(Kotlin files) classified every matching file as follows:

| File under `shared/src` | Classification |
|---|---|
| `commonMain/.../nav/ghost/MagneticMatch.kt` | Implementation: two-channel squared distance, banded recurrence, path-length normalisation. Other hits describe its gates and call site. |
| `commonMain/.../nav/ghost/GhostFilter.kt` | Implementation: squared wrapped-angle cost, banded recurrence, total cost. Other hits are scratch, constants and documentation. |
| `commonMain/.../nav/Navigator.kt` | References only: comments describing relock's turn-signature matching. |
| `commonMain/.../nav/ghost/Observation.kt` | References only: observation documentation. |
| `commonTest/.../nav/MagneticMatchTest.kt` | References: documentation and direct tests of `dtwMeanCost`; no recurrence. |
| `commonTest/.../nav/RelockTest.kt` | Reference: relock documentation. |
| `commonTest/.../nav/FixGateTest.kt` | Reference: fixture comment. |
| `commonTest/.../nav/HitDebounceTest.kt` | Reference: fixture comment. |
| `commonTest/.../transfer/WalkStagerTest.kt` | Incidental match inside encoded fixture data, not a DTW symbol. |

After extraction, `nav/ghost/Dtw.kt` owns the only recurrence. Both callers
supply reusable scratch rows and inline local costs. Magnetic matching
retains its tested `dtwMeanCost` adapter, with scratch reused across all
candidate windows. Navigator has no kernel to migrate.

Compatibility details: strict predecessor comparisons preserve vertical,
then horizontal, then diagonal tie priority. Mean cost remains the
minimum-total-cost path divided by that path's length. It is not a new
minimum-mean-path algorithm. Turn signatures retain unnormalised total
cost and the existing per-window likelihood normalisation.
The legacy non-finite behavior is preserved too: turn totals propagate
NaN unconditionally; magnetic means guard unreachable predecessors.
`nonFiniteTurnCostsRetainHistoricalPropagation` failed before that
distinction was restored, and passes with it.

The host allocation probe measures only the warm shared kernel, including
captured inline local costs. It is not a device timing claim or a claim
that the complete magnetic matcher (which normalises candidate arrays)
is allocation-free. Existing consumer assertions remain unchanged.
