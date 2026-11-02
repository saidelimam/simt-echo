package app.simt.echo.platform

/** Records what a [LiveUpdateDirector] hands the platform, in order. */
class RecordingLiveUpdateSink : LiveUpdateSink {
    val shown = mutableListOf<Pair<Int, LiveUpdatePlan>>()
    val dismissed = mutableListOf<Int>()

    override fun show(notificationId: Int, plan: LiveUpdatePlan) {
        shown += notificationId to plan
    }

    override fun dismiss(notificationId: Int) {
        dismissed += notificationId
    }
}
