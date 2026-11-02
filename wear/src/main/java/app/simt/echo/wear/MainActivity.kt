package app.simt.echo.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

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
 * the cue + distance carry the information in text. The glyph is marked
 * decorative for accessibility — the adjacent text says the same thing.
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
            textSize = 56f
            gravity = Gravity.CENTER
            // Decorative by policy: cueView + distanceView carry the same
            // information as text for TalkBack.
            contentDescription = null
        }
        distanceView = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
        }
        cueView = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
            addView(arrowView)
            addView(distanceView)
            addView(cueView)
        }
        setContentView(layout)
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
