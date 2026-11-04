package app.simt.echo.wear

import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Review 0013 F6 (R13-06): the rotated arrow is decorative in the Android
 * accessibility tree, and the watch content is dp-padded inside a
 * scroll container so a round display or large text cannot clip the cue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityA11yTest {

    @Test
    fun arrowIsExcludedFromTalkBack() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val arrow = activity.findViewById<TextView>(R.id.wear_arrow)
        assertEquals(
            View.IMPORTANT_FOR_ACCESSIBILITY_NO,
            arrow.importantForAccessibility,
        )
    }

    @Test
    fun contentScrollsWithDensityScaledPadding() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val scroll = activity.findViewById<ScrollView>(R.id.wear_scroll)
        assertTrue(scroll.isFillViewport)
        val expected = (WEAR_PADDING_DP * activity.resources.displayMetrics.density).roundToInt()
        assertEquals(expected, scroll.paddingLeft)
        assertEquals(expected, scroll.paddingTop)
        assertEquals(expected, scroll.paddingRight)
        assertEquals(expected, scroll.paddingBottom)
        assertTrue(scroll.getChildAt(0) is android.widget.LinearLayout)
    }

    @Test
    fun cueAndDistanceStayReadableWithoutTheGlyph() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val cue = activity.findViewById<TextView>(R.id.wear_cue)
        assertTrue(cue.text.isNotBlank())
        val distance = activity.findViewById<TextView>(R.id.wear_distance)
        assertTrue("no snapshot yet: distance stays empty", distance.text.isBlank())
    }
}
