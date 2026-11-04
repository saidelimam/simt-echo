package app.simt.echo.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Base padding around the watch content, in dp (never raw pixels). */
internal const val WEAR_PADDING_DP = 16

/**
 * Watch guidance screen (plan W13-12): the latest `/echo/nav` snapshot —
 * arrow, distance and next cue — or the listening state when no route is
 * active on the phone.
 *
 * The screen observes [EchoNavStore] between `onStart` and `onStop`, so a
 * snapshot that lands while it is visible renders at once (not only on the
 * next resume), and a once-a-second tick lets a snapshot that stops
 * arriving read as paused ([EchoNavPresentation.isPaused]).
 *
 * Plain Views by module policy (no Compose runtime on this classpath):
 * the arrow is a rotated glyph, so the heading survives at a glance, and
 * the cue + distance carry the information in text. The glyph is excluded
 * from accessibility with `importantForAccessibility = NO`: a TextView's
 * null contentDescription still falls back to its text, so TalkBack would
 * otherwise announce "Upwards arrow" at every heading.
 *
 * The content sits in a [ScrollView] with dp padding plus system-bar and
 * chin (display-cutout) insets, so a round watch or large font never clips
 * the cue.
 *
 * Owed and NOT claimed here: a Compose for Wear ongoing activity, an
 * active-route tile, and the on-watch run itself (no paired watch in
 * this environment).
 */
class MainActivity : Activity() {

    private lateinit var arrowView: TextView
    private lateinit var distanceView: TextView
    private lateinit var cueView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val navListener = EchoNavStore.Listener { runOnUiThread { render() } }
    private val staleTick = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, STALE_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arrowView = TextView(this).apply {
            id = R.id.wear_arrow
            textSize = 56f
            gravity = Gravity.CENTER
            // Decorative by policy: cueView + distanceView carry the same
            // information as text. A rotated TextView is still a text node,
            // so null contentDescription is not enough (review 0013 F6).
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        distanceView = TextView(this).apply {
            id = R.id.wear_distance
            textSize = 16f
            gravity = Gravity.CENTER
        }
        cueView = TextView(this).apply {
            id = R.id.wear_cue
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(this).apply {
            id = R.id.wear_content
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(arrowView)
            addView(distanceView)
            addView(cueView)
        }
        val pad = (WEAR_PADDING_DP * resources.displayMetrics.density).roundToInt()
        val scroll = ScrollView(this).apply {
            id = R.id.wear_scroll
            isFillViewport = true
            setPadding(pad, pad, pad, pad)
            addView(layout)
        }
        // Chin-safe: a round display cuts the bottom; bars + cutout are the
        // only insets a watch reports.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            view.setPadding(
                bars.left + pad,
                bars.top + pad,
                bars.right + pad,
                bars.bottom + pad,
            )
            insets
        }
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        EchoNavStore.addListener(navListener)
        staleTick.run()
    }

    override fun onStop() {
        handler.removeCallbacks(staleTick)
        EchoNavStore.removeListener(navListener)
        super.onStop()
    }

    private fun render() {
        val state = EchoNavStore.latest
        val receivedAtMs = EchoNavStore.latestReceivedAtMs
        if (state == null || receivedAtMs == null) {
            arrowView.text = ""
            arrowView.rotation = 0f
            arrowView.alpha = 1f
            distanceView.text = ""
            cueView.text = getString(R.string.wear_listening)
            return
        }
        val paused = EchoNavPresentation.isPaused(state, receivedAtMs, EchoNavStore.nowMs())
        arrowView.text = ARROW_GLYPH
        arrowView.rotation = state.arrowDeg.toFloat()
        // A paused arrow is a last-known heading, not a live one: dim it.
        arrowView.alpha = if (paused) PAUSED_ALPHA else 1f
        val label = if (paused) EchoNavPhaseLabel.PAUSED else EchoNavPresentation.label(state.phase)
        distanceView.text = if (label == null) {
            getString(R.string.wear_nav_distance_only, state.distanceM)
        } else {
            getString(R.string.wear_nav_distance, state.distanceM, getString(labelRes(label)))
        }
        cueView.text = state.cue.ifBlank { getString(R.string.wear_listening) }
    }

    private fun labelRes(label: EchoNavPhaseLabel): Int = when (label) {
        EchoNavPhaseLabel.ALIGNING -> R.string.wear_phase_aligning
        EchoNavPhaseLabel.WALKING -> R.string.wear_phase_walking
        EchoNavPhaseLabel.APPROACHING -> R.string.wear_phase_approaching
        EchoNavPhaseLabel.TURNING -> R.string.wear_phase_turning
        EchoNavPhaseLabel.CONFIRMING -> R.string.wear_phase_confirming
        EchoNavPhaseLabel.UNSURE -> R.string.wear_phase_unsure
        EchoNavPhaseLabel.OFF_ROUTE -> R.string.wear_phase_off_route
        EchoNavPhaseLabel.ARRIVED -> R.string.wear_phase_arrived
        EchoNavPhaseLabel.FINDING -> R.string.wear_phase_finding
        EchoNavPhaseLabel.PAUSED -> R.string.wear_phase_paused
    }

    private companion object {
        const val ARROW_GLYPH = "↑"
        const val STALE_TICK_MS = 1_000L
        const val PAUSED_ALPHA = 0.4f
    }
}
