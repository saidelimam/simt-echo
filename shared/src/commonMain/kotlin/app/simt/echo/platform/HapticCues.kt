package app.simt.echo.platform

/**
 * Haptic + eyes-free language for navigation cues (plan W13-09, per the
 * W10-07 Luminous freeze §7 grammar, waveforms inherited from V2 §8.4).
 *
 * Honest-light mapping — every cue is bound to a real [app.simt.echo.nav]
 * signal, emitted by `Navigator` as `NavEvent.Haptic` and played by the
 * screen's [Haptics]:
 *
 * | Cue | Nav signal | Freeze composition |
 * |---|---|---|
 * | TICK | Null turn / distance heartbeat tick | `TICK` 0.6 |
 * | CONFIRM | Walker confirmed a landmark (`SeeLandmark`) | `CLICK` 0.8 → 60 ms → `CLICK` 0.5 |
 * | LEFT | Next turn is a left | `QUICK_RISE` 0.6 → `CLICK` 1.0 |
 * | RIGHT | Next turn is a right | `CLICK` 0.8 → 60 ms → `CLICK` 0.8 → `QUICK_RISE` 0.6 |
 * | U_TURN | Next turn is a U-turn | `SLOW_RISE` 0.8 → `THUD` 1.0 |
 * | WARN | Uncertain / off-route | `THUD` 0.7 → 120 ms → `THUD` 0.7 |
 * | ARRIVE | Route complete | `QUICK_RISE` 0.5 → `CLICK` 0.7 → 80 ms → `SPIN` 0.6 (bloom) |
 *
 * The heartbeat is not a cue: the caller replays TICK on the schedule from
 * [DistanceHeartbeat] while closing on a turn. The Wear mirror receives the
 * same cue ids (`WearMirroringHaptics` forwards every [HapticPattern]);
 * composition happens on the phone, the watch plays its §8.4 waveform.
 *
 * TBD (owed device evidence): cues judged distinguishable by two people,
 * recorded — see plan W13-09 acceptance. This file asserts the *intended*
 * patterns; human distinguishability still needs the on-device trial.
 */
enum class HapticPrimitive {
    TICK,
    CLICK,
    QUICK_RISE,
    SLOW_RISE,
    THUD,
    SPIN,
}

/**
 * One composition step: play [primitive] at [scale] (0.0–1.0) after
 * [delayMs] milliseconds of silence following the previous step.
 */
data class PrimitiveStep(
    val primitive: HapticPrimitive,
    val scale: Float,
    val delayMs: Long = 0L,
)

/** Platform-neutral effect: composition primitives when supported, else a waveform. */
sealed interface HapticEffect {
    data class Composition(val steps: List<PrimitiveStep>) : HapticEffect
    data class Waveform(val timings: LongArray) : HapticEffect {
        override fun equals(other: Any?): Boolean =
            other is Waveform && timings.contentEquals(other.timings)
        override fun hashCode(): Int = timings.contentHashCode()
    }
}

/** Cue → primitive-composition map with waveform fallback (freeze §7). */
object HapticCueMap {

    /** Primitive composition for [pattern], verbatim from freeze §7. */
    fun composition(pattern: HapticPattern): List<PrimitiveStep> = when (pattern) {
        HapticPattern.TICK ->
            listOf(PrimitiveStep(HapticPrimitive.TICK, 0.6f))
        HapticPattern.CONFIRM ->
            listOf(
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.5f, delayMs = 60L),
            )
        HapticPattern.LEFT ->
            listOf(
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.6f),
                PrimitiveStep(HapticPrimitive.CLICK, 1.0f),
            )
        HapticPattern.RIGHT ->
            listOf(
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.8f, delayMs = 60L),
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.6f),
            )
        HapticPattern.U_TURN ->
            listOf(
                PrimitiveStep(HapticPrimitive.SLOW_RISE, 0.8f),
                PrimitiveStep(HapticPrimitive.THUD, 1.0f),
            )
        HapticPattern.WARN ->
            listOf(
                PrimitiveStep(HapticPrimitive.THUD, 0.7f),
                PrimitiveStep(HapticPrimitive.THUD, 0.7f, delayMs = 120L),
            )
        HapticPattern.ARRIVE ->
            listOf(
                PrimitiveStep(HapticPrimitive.QUICK_RISE, 0.5f),
                PrimitiveStep(HapticPrimitive.CLICK, 0.7f),
                PrimitiveStep(HapticPrimitive.SPIN, 0.6f, delayMs = 80L),
            )
    }

    /** Distinct primitives [pattern] needs; the platform queries support for exactly these. */
    fun requiredPrimitives(pattern: HapticPattern): Set<HapticPrimitive> =
        composition(pattern).map { it.primitive }.toSet()

    /**
     * Waveform fallback for [pattern] (V2 §8.4 timings, unchanged).
     * Must stay identical to the watch-side `EchoWearHaptics` vocabulary so
     * phone fallback and watch mirror feel the same.
     */
    fun fallbackWaveform(pattern: HapticPattern): LongArray = when (pattern) {
        HapticPattern.TICK -> longArrayOf(0, 30)
        HapticPattern.CONFIRM -> longArrayOf(0, 40, 80, 40)
        HapticPattern.LEFT -> longArrayOf(0, 60, 120, 60)
        HapticPattern.RIGHT -> longArrayOf(0, 60, 120, 60, 120, 60)
        HapticPattern.U_TURN -> longArrayOf(0, 300, 150, 300)
        HapticPattern.WARN -> longArrayOf(0, 400)
        HapticPattern.ARRIVE -> longArrayOf(0, 200, 120, 60, 120, 200)
    }

    /**
     * Effect for [pattern]: composition when [primitivesSupported] (the
     * caller gates this on `areAllPrimitivesSupported` for
     * [requiredPrimitives]), else the waveform fallback.
     */
    fun effectFor(pattern: HapticPattern, primitivesSupported: Boolean): HapticEffect =
        if (primitivesSupported) {
            HapticEffect.Composition(composition(pattern))
        } else {
            HapticEffect.Waveform(fallbackWaveform(pattern))
        }
}

/**
 * Distance heartbeat (freeze §7): TICK interval quickens from 1.2 s to
 * 0.4 s over the last 8 m to a turn. Linear in remaining distance — the
 * freeze names only the endpoints, so the simplest honest curve wins.
 * The caller schedules TICK replays at the returned interval.
 */
object DistanceHeartbeat {
    const val FAR_INTERVAL_MS = 1200L
    const val NEAR_INTERVAL_MS = 400L
    const val RANGE_M = 8.0

    /** Interval in ms for [remainingM] metres to the turn; clamped to the 8 m window. */
    fun intervalMs(remainingM: Double): Long {
        val clamped = remainingM.coerceIn(0.0, RANGE_M)
        return NEAR_INTERVAL_MS + ((clamped / RANGE_M) * (FAR_INTERVAL_MS - NEAR_INTERVAL_MS)).toLong()
    }
}

/**
 * Eyes-free announcement filter (plan W13-09): in eyes-free mode only
 * decision points reach the announcement channel. Confirmations and
 * heartbeat ticks are felt (haptics), never spoken, so the channel stays
 * quiet while walking straight.
 */
object EyesFreeFilter {
    /** True when [pattern] is a decision point worth announcing eyes-free. */
    fun isDecisionPoint(pattern: HapticPattern): Boolean = when (pattern) {
        HapticPattern.LEFT,
        HapticPattern.RIGHT,
        HapticPattern.U_TURN,
        HapticPattern.WARN,
        HapticPattern.ARRIVE,
        -> true
        HapticPattern.TICK,
        HapticPattern.CONFIRM,
        -> false
    }
}
