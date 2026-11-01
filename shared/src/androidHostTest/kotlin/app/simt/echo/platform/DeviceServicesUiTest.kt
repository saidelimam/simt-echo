package app.simt.echo.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import app.simt.echo.host.RobolectricActivityRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Headless device-services verification (W6-03/W7-02, plan W2-06).
 *
 * [KeepScreenOn] must hold the window flag only while composed;
 * [rememberOnline] must follow the shadow connectivity manager from
 * offline back to online. Both run against the real Android actuals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class DeviceServicesUiTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private val hostActivity get() = activityRule.activity

    @Test
    fun keepScreenOn_holdsFlagOnlyWhileComposed() {
        var show by mutableStateOf(true)
        compose.setContent {
            if (show) KeepScreenOn()
        }
        compose.waitForIdle()
        val flags = hostActivity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)

        show = false
        compose.waitForIdle()
        val cleared = hostActivity.window.attributes.flags
        assertTrue(cleared and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0)
    }

    @Test
    fun rememberOnline_followsConnectivity() {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = Shadows.shadowOf(manager)
        shadow.setActiveNetworkInfo(null)

        var online = true
        compose.setContent { online = rememberOnline(pollMs = 50L) }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertFalse(online)

        shadow.setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(
                NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                NetworkInfo.State.CONNECTED,
            ),
        )
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertTrue(online)
    }
}
