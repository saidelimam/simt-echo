package app.simt.echo.wear

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Watch guidance screen (plan W13-12): the latest `/echo/nav` snapshot —
 * arrow, distance and next cue — or the listening state when no route is
 * active on the phone.
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

    override fun onResume() {
        super.onResume()
        render(EchoNavStore.latest)
    }

    private fun render(state: EchoNavState?) {
        if (state == null) {
            arrowView.text = ""
            arrowView.rotation = 0f
            distanceView.text = ""
            cueView.text = getString(R.string.wear_listening)
            return
        }
        arrowView.text = ARROW_GLYPH
        arrowView.rotation = state.arrowDeg.toFloat()
        distanceView.text = getString(R.string.wear_nav_distance, state.distanceM, state.phase)
        cueView.text = state.cue.ifBlank { getString(R.string.wear_listening) }
    }

    private companion object {
        const val ARROW_GLYPH = "↑"
    }
}
