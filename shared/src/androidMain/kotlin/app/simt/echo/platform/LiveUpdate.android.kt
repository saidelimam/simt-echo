package app.simt.echo.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Android renderer for the W13-10 [LiveUpdatePlan]: `ProgressStyle`
 * (segments = legs, points = landmarks) on Android 16+, standard ongoing
 * `setProgress` below. Thin by design — every branchable decision lives in
 * the common model, so this class holds no logic worth host-testing beyond
 * what its Robolectric test pins (privacy, promotion).
 *
 * Privacy: the notification is `VISIBILITY_PRIVATE` and carries a public
 * version that says only what [LiveUpdateContent.publicText] allows
 * ("Navigating") — the route name and the turn instruction, which can name
 * a landmark, never show on a locked screen.
 *
 * On Android 16+ the notification asks to be promoted ongoing (a Live
 * Update proper) when the app may post one; otherwise it posts as the
 * ordinary ongoing notification it already is.
 *
 * No foreground service (screen-on-only decision, recorded in the file
 * KDoc on `LiveUpdate.kt`): plain `notify` while the app is alive.
 * Without `POST_NOTIFICATIONS` the post is skipped silently instead of
 * throwing; the screens degrade honestly to no notification.
 *
 * [smallIcon] is the app's own monochrome status icon (the Android entry
 * point passes `R.drawable.ic_stat_echo`).
 */
class AndroidLiveUpdateSink(
    private val context: Context,
    private val smallIcon: Int,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LiveUpdateSink {

    override fun show(notificationId: Int, plan: LiveUpdatePlan) {
        ensureChannel()
        if (sdkInt >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(notificationId, build(plan))
    }

    internal fun build(plan: LiveUpdatePlan): Notification {
        val builder = NotificationCompat.Builder(context, LIVE_UPDATE_CHANNEL_ID)
            .setContentTitle(plan.content.title)
            .setContentText(plan.content.text)
            .setSmallIcon(smallIcon)
            .setOngoing(plan.content.ongoing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(plan.content.publicText))
        if (plan.style == LiveUpdateStyle.PROGRESS) {
            val style = NotificationCompat.ProgressStyle()
            plan.content.segments.forEachIndexed { index, _ ->
                style.addProgressSegment(
                    NotificationCompat.ProgressStyle.Segment(plan.segmentUnits[index]).setId(index),
                )
            }
            plan.content.points.forEachIndexed { index, _ ->
                style.addProgressPoint(
                    NotificationCompat.ProgressStyle.Point(plan.pointUnits[index]).setId(index),
                )
            }
            if (plan.indeterminate) {
                style.setProgressIndeterminate(true)
            } else {
                style.setProgress(plan.progressUnits)
            }
            builder.setStyle(style)
            if (canPromote()) builder.setRequestPromotedOngoing(true)
        } else {
            builder.setProgress(plan.progressMaxUnits, plan.progressUnits, plan.indeterminate)
        }
        return builder.build()
    }

    /** The lock-screen version: the app name and [text], nothing about the route. */
    private fun publicVersion(text: String): Notification =
        NotificationCompat.Builder(context, LIVE_UPDATE_CHANNEL_ID)
            .setContentTitle(context.applicationInfo.loadLabel(context.packageManager))
            .setContentText(text)
            .setSmallIcon(smallIcon)
            .build()

    /** Promotion is a 16+ request the user can switch off per app. */
    private fun canPromote(): Boolean =
        sdkInt >= LIVE_UPDATE_SDK_PROGRESS &&
            context.getSystemService(NotificationManager::class.java)?.canPostPromotedNotifications() == true

    override fun dismiss(notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(LIVE_UPDATE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    LIVE_UPDATE_CHANNEL_ID,
                    "Live updates",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}
