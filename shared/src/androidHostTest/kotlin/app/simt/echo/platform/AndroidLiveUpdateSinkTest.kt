package app.simt.echo.platform

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W13-10: what the navigation notification shows on a locked screen, and
 * which icon it wears. Runs the real [AndroidLiveUpdateSink] against the
 * Robolectric notification manager (POST_NOTIFICATIONS granted where a test posts).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidLiveUpdateSinkTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val icon = 0x7f080001

    private fun navPlan(): LiveUpdatePlan {
        val ui = NavUi(
            phase = NavPhase.WALK,
            legIndex = 0,
            instruction = "Turn left at the Red door in 12 m",
            distanceToNextM = 12.0,
            arrowRad = 0.0,
            nextTurn = null,
            landmark = null,
            uncertaintyM = 1.0,
            progress01 = 0.4,
        )
        return planLiveUpdate(
            liveUpdateForNav(ui, listOf(10.0, 20.0), listOf(LandmarkMark(10.0, "Red door")), "Evening loop"),
            sdkInt = 34,
        )
    }

    @Test
    fun theLockScreenVersionSaysOnlyNavigating() {
        val notification = AndroidLiveUpdateSink(context, icon, sdkInt = 34).build(navPlan())

        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        val public = assertNotNull(notification.publicVersion, "no lock-screen version")
        val text = public.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        assertEquals("Navigating", text)
        val everything = listOfNotNull(
            public.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text,
        ).joinToString(" ")
        assertFalse(everything.contains("Evening loop"), "route name leaked: $everything")
        assertFalse(everything.contains("Red door"), "instruction leaked: $everything")
        assertFalse(everything.contains("Turn left"), "instruction leaked: $everything")
    }

    @Test
    fun theOwnerStillSeesTheRouteAndTheInstruction() {
        val notification = AndroidLiveUpdateSink(context, icon, sdkInt = 34).build(navPlan())
        assertEquals("Evening loop", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(
            "Turn left at the Red door in 12 m",
            notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        )
    }

    @Test
    fun wearsTheAppsOwnSmallIconNotAFrameworkDialogIcon() {
        val notification = AndroidLiveUpdateSink(context, icon, sdkInt = 34).build(navPlan())
        assertEquals(icon, notification.smallIcon.resId)
        assertNotEquals(android.R.drawable.ic_dialog_info, notification.smallIcon.resId)
        assertEquals(icon, assertNotNull(notification.publicVersion).smallIcon.resId)
    }

    @Test
    fun postsAnOngoingNotificationAndDismissCancelsIt() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val sink = AndroidLiveUpdateSink(context, icon, sdkInt = 34)
        sink.show(NAV_LIVE_UPDATE_ID, navPlan())

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = Shadows.shadowOf(manager).getNotification(NAV_LIVE_UPDATE_ID)
        assertNotNull(posted)
        assertTrue(posted.flags and Notification.FLAG_ONGOING_EVENT != 0, "not ongoing")
        assertEquals(NotificationCompat.VISIBILITY_PRIVATE, posted.visibility)

        sink.dismiss(NAV_LIVE_UPDATE_ID)
        assertEquals(null, Shadows.shadowOf(manager).getNotification(NAV_LIVE_UPDATE_ID))
    }
}
