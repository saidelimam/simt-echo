package app.simt.echo.nav

import app.simt.echo.platform.EchoWearPaths
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Watch nav mirror (plan W13-12): arrow, distance, next cue and phase,
 * synced over [EchoWearPaths.MESSAGE_PATH_NAV], change-driven at ≤ 1 Hz.
 *
 * Wire format (v1, UTF-8, mirrored by the watch-side `EchoNavCodec` — the
 * two are tested round-trip on both sides and must change together):
 *
 * ```
 * v1\n<arrowDeg>\n<distanceM>\n<phase>\n<escaped cue>
 * ```
 *
 * Five lines: magic, arrow degrees (-180..180), distance metres (≥ 0),
 * phase name, cue with `\` → `\\` and newline → `\n` escapes. Decode is
 * strict on structure (anything else → null, never throws) and lenient on
 * content (an unknown phase name is kept verbatim so a newer phone never
 * breaks an older watch).
 *
 * Haptics are NOT on this path: W13-09 cues already ride `/echo/haptics`
 * via `WearMirroringHaptics`; the watch plays its §8.4 waveform there.
 */
data class NavWearState(
    val arrowDeg: Int,
    val distanceM: Int,
    val cue: String,
    val phase: String,
)

/**
 * The final snapshot of a walk that ended without arriving (screen left,
 * route abandoned): the watch drops the guidance and goes back to
 * listening instead of showing a stale arrow forever.
 */
fun endedWearState(): NavWearState =
    NavWearState(arrowDeg = 0, distanceM = 0, cue = "", phase = WearNavSync.PHASE_ENDED)

/** Maps the rendered [NavUi] onto the quantized watch snapshot. */
fun NavUi.toWearState(): NavWearState {
    val raw = (arrowRad * 180.0 / PI).roundToInt()
    val normalized = ((raw + 180) % 360 + 360) % 360 - 180
    val quantum = WearNavSync.ARROW_QUANTUM_DEG
    val arrowDeg = (normalized / quantum) * quantum
    return NavWearState(
        arrowDeg = arrowDeg,
        distanceM = distanceToNextM.roundToInt().coerceAtLeast(0),
        cue = instruction.take(WearNavSync.MAX_CUE_CHARS),
        phase = phase.name,
    )
}

/** Pure codec + send gate for the `/echo/nav` mirror. */
object WearNavSync {
    /** Data Layer path for nav snapshots (single source: [EchoWearPaths]). */
    const val MESSAGE_PATH: String = EchoWearPaths.MESSAGE_PATH_NAV

    /** Arrow quantization step in degrees: smaller turns never re-send. */
    const val ARROW_QUANTUM_DEG = 5

    /** Cue text cap in chars; longer instructions truncate on encode. */
    const val MAX_CUE_CHARS = 140

    /** Minimum interval between sends in ms (the ≤ 1 Hz rule). */
    const val MIN_SEND_INTERVAL_MS = 1000L

    /** Phase of the closing snapshot ([endedWearState]); not a [NavPhase]. */
    const val PHASE_ENDED = "ENDED"

    private const val MAGIC = "v1"

    fun encode(state: NavWearState): ByteArray {
        val body = listOf(
            MAGIC,
            state.arrowDeg.toString(),
            state.distanceM.toString(),
            state.phase,
            escape(state.cue.take(MAX_CUE_CHARS)),
        ).joinToString("\n")
        return body.encodeToByteArray()
    }

    /** Decodes a snapshot, or null when the payload is not v1 nav. Never throws. */
    fun decode(bytes: ByteArray): NavWearState? = runCatching {
        decodeText(bytes.decodeToString())
    }.getOrNull()

    private fun decodeText(text: String): NavWearState? {
        val lines = splitLines(text)
        if (lines.size != 5 || lines[0] != MAGIC) return null
        val arrow = lines[1].toIntOrNull() ?: return null
        if (arrow < -180 || arrow > 180) return null
        val distance = lines[2].toIntOrNull() ?: return null
        if (distance < 0 || distance > 999_999) return null
        val phase = lines[3]
        if (phase.isEmpty() || phase.length > 64) return null
        val cue = unescape(lines[4]) ?: return null
        if (cue.length > 512) return null
        return NavWearState(arrow, distance, cue, phase)
    }

    private fun escape(raw: String): String = buildString(raw.length) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }
    }

    private fun unescape(raw: String): String? {
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\') {
                if (i + 1 >= raw.length) return null
                when (raw[i + 1]) {
                    '\\' -> out.append('\\')
                    'n' -> out.append('\n')
                    else -> return null
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun splitLines(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') {
                out += current.toString()
                current.clear()
            } else if (c == '\\' && i + 1 < text.length && text[i + 1] == 'n') {
                // An escaped newline belongs to the field, not the framing —
                // but only inside the cue (last line). Re-emit verbatim and
                // let the field unescaper sort it out.
                current.append("\\n")
                i++
            } else if (c == '\\' && i + 1 < text.length && text[i + 1] == '\\') {
                current.append("\\\\")
                i++
            } else {
                current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }
}

/**
 * Change-driven ≤ 1 Hz send gate (plan W13-12).
 *
 * First snapshot always sends. Afterwards a send happens only when the
 * snapshot differs from the last sent one AND at least
 * [WearNavSync.MIN_SEND_INTERVAL_MS] elapsed since that send. Time is a
 * parameter so the rule is unit-testable without clocks.
 *
 * A changed snapshot dropped for arriving too soon is not lost: the gate
 * remembers the latest one and offers it as a trailing send once the
 * interval is up ([trailingDelayMs] / [takeTrailing]). Without it, the last
 * change before a pause (the turn cue, the arrival) never reached the
 * watch.
 */
class WearNavSendGate {
    private var lastSent: NavWearState? = null
    private var lastSentMs: Long? = null
    private var pending: NavWearState? = null

    fun shouldSend(nowMs: Long, candidate: NavWearState): Boolean {
        val last = lastSent
        if (last != null && last == candidate) {
            // Back to what the watch already shows: nothing left to trail.
            pending = null
            return false
        }
        val at = lastSentMs
        if (last != null && at != null && nowMs - at < WearNavSync.MIN_SEND_INTERVAL_MS) {
            pending = candidate
            return false
        }
        pending = null
        lastSent = candidate
        lastSentMs = nowMs
        return true
    }

    /** Milliseconds until the trailing send is due (0 when due now), or null when none is pending. */
    fun trailingDelayMs(nowMs: Long): Long? {
        if (pending == null) return null
        val at = lastSentMs ?: return 0L
        return (WearNavSync.MIN_SEND_INTERVAL_MS - (nowMs - at)).coerceAtLeast(0L)
    }

    /** The latest dropped snapshot once the interval is up (marking it sent), else null. */
    fun takeTrailing(nowMs: Long): NavWearState? {
        val candidate = pending ?: return null
        val at = lastSentMs
        if (at != null && nowMs - at < WearNavSync.MIN_SEND_INTERVAL_MS) return null
        pending = null
        lastSent = candidate
        lastSentMs = nowMs
        return candidate
    }
}
