package app.simt.echo.platform

import android.Manifest
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
 * the common model, so this class holds no logic worth host-testing.
 *
 * No foreground service (screen-on-only decision, recorded in the file
 * KDoc on `LiveUpdate.kt`): plain `notify` while the app is alive.
 * Without `POST_NOTIFICATIONS` the post is skipped silently instead of
 * throwing; the screens degrade honestly to no notification.
 */
class AndroidLiveUpdateSink(
    private val context: Context,
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
        val builder = NotificationCompat.Builder(context, LIVE_UPDATE_CHANNEL_ID)
            .setContentTitle(plan.content.title)
            .setContentText(plan.content.text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(plan.content.ongoing)
            .setOnlyAlertOnce(true)
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
        } else {
            builder.setProgress(plan.progressMaxUnits, plan.progressUnits, plan.indeterminate)
        }
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

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
