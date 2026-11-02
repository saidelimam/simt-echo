package app.simt.echo

import android.app.Activity
import android.util.Log
import androidx.metrics.performance.JankStats
import java.util.concurrent.atomic.AtomicLong

/**
 * W10-04: debug-only JankStats summaries.
 *
 * Lives in the `debug` source set, so it cannot ship in release; the
 * release build never loads `androidx.metrics`. Installed from
 * [MainActivity] via reflection, keeping the main source set free of a
 * debug-only reference.
 *
 * Summaries go to logcat (`EchoJank`): one line per 300 frames with the
 * cumulative janky-frame share for the window. On-device runs pair these
 * with the `:benchmark` macrobenchmark JSON (docs/perf.md, gate G-PERF-05);
 * the W10-01 baselines in docs/measurements.md are the comparison point.
 */
object DebugJankStats {
    private const val TAG = "EchoJank"
    private const val FRAMES_PER_SUMMARY = 300L

    private val frames = AtomicLong(0)
    private val jankyFrames = AtomicLong(0)

    /** Installed reflectively from MainActivity when [BuildConfig.DEBUG]. */
    @JvmStatic
    fun install(activity: Activity) {
        val window = activity.window ?: return
        try {
            JankStats.createAndTrack(window) { frameData ->
                val total = frames.incrementAndGet()
                if (frameData.isJank) jankyFrames.incrementAndGet()
                if (total % FRAMES_PER_SUMMARY == 0L) {
                    val janky = jankyFrames.get()
                    val share = janky * 100.0 / total
                    Log.d(TAG, "window=" + activity.javaClass.simpleName +
                        " frames=" + total +
                        " janky=" + janky +
                        " share=" + "%.2f".format(share) + "%")
                }
            }.isTrackingEnabled = true
        } catch (e: IllegalStateException) {
            // No decor view yet (window not active): skip, never crash launch.
            Log.w(TAG, "JankStats not attached", e)
        }
    }
}
