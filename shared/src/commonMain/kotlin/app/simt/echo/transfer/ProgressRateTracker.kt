package app.simt.echo.transfer

import app.simt.echo.platform.TransferProgress
import app.simt.echo.platform.bytesPerSecond
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Silence after which a transfer that has not finished counts as stalled. */
const val TRANSFER_STALL_MS = 1_000L

/**
 * Measures the live transfer rate for the particle flow (W13-14, honest
 * light) and notices when it stops. A rate is only ever recomputed when a
 * chunk arrives, so each sample re-arms a one-shot stall timer on [scope]:
 * if no further sample comes within [stallAfterMs] the transfer is
 * stalled and [record]'s `onStall` zeroes the rate. A finished transfer
 * arms nothing — it rests lit by completion, not by a rate.
 *
 * Time comes from [nowMs] (rate maths) and the scope's dispatcher (stall
 * timer), both injectable for tests.
 */
class ProgressRateTracker(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val stallAfterMs: Long = TRANSFER_STALL_MS,
) {
    private var last: TransferProgress? = null
    private var lastMs: Long = 0L
    private var stallTimer: Job? = null

    /** Records [emitted] and returns the measured bytes/s since the previous sample. */
    fun record(emitted: TransferProgress, onStall: () -> Unit): Double {
        val now = nowMs()
        val rate = last?.let { bytesPerSecond(it, lastMs, emitted, now) } ?: 0.0
        last = emitted
        lastMs = now
        stallTimer?.cancel()
        stallTimer = if (emitted.isComplete) {
            null
        } else {
            scope.launch {
                delay(stallAfterMs.milliseconds)
                onStall()
            }
        }
        return rate
    }

    /** Forgets the previous sample so a new transfer starts from frozen light. */
    fun reset() {
        last = null
        lastMs = 0L
        stallTimer?.cancel()
        stallTimer = null
    }
}
