package app.simt.echo.platform

import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.transfer.JobStage
import app.simt.echo.ui.screens.processing.ProcessingPhase
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Live Updates content model (plan W13-10, Lane A).
 *
 * Pure commonMain model: legs become progress segments, landmarks become
 * progress points, and the turn text rides as the notification text
 * ("Turn left in 12 m" is [NavUi.instruction], owned by the Navigator —
 * never reworded here). The Android sink renders this model as a
 * `ProgressStyle` notification on Android 16+ and a standard ongoing
 * notification below; host tests drive [LiveUpdateDirector] with a fake
 * [LiveUpdateSink] and a faked SDK int.
 *
 * Privacy: points carry landmark *titles* only — never a photo, crop, or
 * asset id. No lock-screen landmark photos, ever.
 *
 * Service-type decision (S-reviewed slot, 2.0): **screen-on only, no
 * foreground service**. Rationale, recorded here so a later slice can
 * revisit it without re-litigating:
 * 1. Navigation in 2.0 already requires the screen (the arrow UI is the
 *    safety surface); screen-off guidance is out of scope.
 * 2. A location-type foreground service needs FOREGROUND_SERVICE_LOCATION
 *    plus a Play Console foreground-service-type declaration and review
 *    burden — disproportionate for a cuttable 2.0 slice.
 * 3. Battery cost of screen-off tracking is unbudgeted (W14-01 owns
 *    budgets); claiming background navigation without measuring it would
 *    be dishonest.
 * 4. Cuttable by construction: if a later slice adds the service, this
 *    content model and the director/sink seam do not change — only the
 *    Android publisher gains a service wrapper.
 * Honest behavior: updates post while the app is alive and stop when the
 * process stops; no copy implies otherwise.
 */
const val LIVE_UPDATE_CHANNEL_ID = "echo_live_updates"

/** Ongoing navigation notification id (stable across updates). */
const val NAV_LIVE_UPDATE_ID = 1001

/** Ongoing processing notification id (stable across updates). */
const val PROCESSING_LIVE_UPDATE_ID = 1002

/** First SDK with platform Live Updates (`Notification.ProgressStyle`). */
const val LIVE_UPDATE_SDK_PROGRESS = 36

/** One route leg as a progress segment. */
data class LiveUpdateSegment(
    val lengthM: Double,
    val done: Boolean,
    val current: Boolean,
)

/**
 * One landmark as a progress point.
 *
 * [label] is the landmark title for accessibility/device surfaces only;
 * it never references a photo, crop, or asset (privacy rule above).
 */
data class LiveUpdatePoint(
    val positionM: Double,
    val label: String,
)

/** Render-ready notification content, platform-agnostic. */
data class LiveUpdateContent(
    val title: String,
    val text: String,
    val segments: List<LiveUpdateSegment>,
    val points: List<LiveUpdatePoint>,
    /** 0..1 overall progress; null when indeterminate. */
    val progress01: Float?,
    val ongoing: Boolean = true,
    /**
     * All a locked screen may show (the notification's public version).
     * [title] is a route name and [text] a navigation instruction that can
     * name landmarks, so neither belongs on a lock screen: this is the
     * only line that does, and it never carries either.
     */
    val publicText: String = LIVE_UPDATE_PUBLIC_TEXT,
)

/** Fallback lock-screen line when a content builder names none. */
const val LIVE_UPDATE_PUBLIC_TEXT = "ECHO"

/** Lock-screen line for an active walk: says it is navigating, nothing about where. */
const val LIVE_UPDATE_PUBLIC_NAV_TEXT = "Navigating"

/** Lock-screen line while a walk is processing. */
const val LIVE_UPDATE_PUBLIC_PROCESSING_TEXT = "Processing a walk"

/** Landmark position in route metres, paired with its title. */
data class LandmarkMark(
    val positionM: Double,
    val title: String,
)

/**
 * Navigation content: segments = legs (done/current from [NavUi.legIndex]),
 * points = landmarks, text = the Navigator's own instruction verbatim.
 */
fun liveUpdateForNav(
    ui: NavUi,
    legLengthsM: List<Double>,
    landmarks: List<LandmarkMark>,
    routeName: String,
): LiveUpdateContent {
    val segments = legLengthsM.mapIndexed { index, length ->
        LiveUpdateSegment(
            lengthM = length,
            done = index < ui.legIndex,
            current = index == ui.legIndex,
        )
    }
    val total = legLengthsM.sum().takeIf { it > 0.0 }
    val doneM = legLengthsM.take(ui.legIndex.coerceIn(0, legLengthsM.size)).sum()
    val currentLegM = legLengthsM.getOrNull(ui.legIndex) ?: 0.0
    val progress = total?.let { ((doneM + ui.progress01 * currentLegM) / it).toFloat().coerceIn(0f, 1f) } ?: 0f
    return LiveUpdateContent(
        title = routeName,
        text = ui.instruction,
        segments = segments,
        points = landmarks.map { LiveUpdatePoint(it.positionM, it.title) },
        progress01 = progress,
        publicText = LIVE_UPDATE_PUBLIC_NAV_TEXT,
    )
}

/**
 * Processing content: ongoing during [ProcessingPhase.Uploading] and
 * [ProcessingPhase.Working]; null for every settled phase (consent,
 * ready, failed, unavailable) — the caller dismisses instead of posting.
 */
fun liveUpdateForProcessing(phase: ProcessingPhase): LiveUpdateContent? = when (phase) {
    is ProcessingPhase.Uploading -> LiveUpdateContent(
        title = "Processing walk",
        text = "Uploading…",
        segments = emptyList(),
        points = emptyList(),
        progress01 = null,
        publicText = LIVE_UPDATE_PUBLIC_PROCESSING_TEXT,
    )
    is ProcessingPhase.Working -> {
        val fraction = processingFraction(phase.stage) ?: return null
        LiveUpdateContent(
            title = "Processing walk",
            text = processingCopy(phase.stage),
            segments = emptyList(),
            points = emptyList(),
            progress01 = fraction,
            publicText = LIVE_UPDATE_PUBLIC_PROCESSING_TEXT,
        )
    }
    else -> null
}

/** Determinate share per job stage; ERRORED/COMPLETE never render ongoing. */
fun processingFraction(stage: JobStage): Float? = when (stage) {
    JobStage.QUEUED -> 0.05f
    JobStage.READING -> 0.25f
    JobStage.SCOUTING -> 0.5f
    JobStage.GROUNDING -> 0.75f
    JobStage.COMPLETE, JobStage.ERRORED -> null
}

/** One-line stage copy for the notification text. */
fun processingCopy(stage: JobStage): String = when (stage) {
    JobStage.QUEUED -> "Queued…"
    JobStage.READING -> "Reading photos…"
    JobStage.SCOUTING -> "Scouting landmarks…"
    JobStage.GROUNDING -> "Grounding landmarks…"
    JobStage.COMPLETE -> "Done"
    JobStage.ERRORED -> "Failed"
}

/** Which notification shape the SDK gets. */
enum class LiveUpdateStyle {
    PROGRESS,
    LEGACY_ONGOING,
}

/** Android 16+ gets `ProgressStyle`; everything below gets standard ongoing. */
fun liveUpdateStyleFor(sdkInt: Int): LiveUpdateStyle =
    if (sdkInt >= LIVE_UPDATE_SDK_PROGRESS) LiveUpdateStyle.PROGRESS else LiveUpdateStyle.LEGACY_ONGOING

/**
 * Integer render plan for the Android sink: segment lengths in whole units
 * (each >= 1, since `Segment` requires length >= 1), point positions in the
 * same unit space, and progress in that space. Segment-less content
 * (processing) uses a 0..100 space, matching the legacy `setProgress` max.
 */
data class LiveUpdatePlan(
    val content: LiveUpdateContent,
    val style: LiveUpdateStyle,
    val indeterminate: Boolean,
    val segmentUnits: List<Int>,
    val pointUnits: List<Int>,
    val progressUnits: Int,
    val progressMaxUnits: Int,
)

fun planLiveUpdate(content: LiveUpdateContent, sdkInt: Int): LiveUpdatePlan {
    val indeterminate = content.progress01 == null
    if (content.segments.isEmpty()) {
        val max = 100
        return LiveUpdatePlan(
            content = content,
            style = liveUpdateStyleFor(sdkInt),
            indeterminate = indeterminate,
            segmentUnits = emptyList(),
            pointUnits = emptyList(),
            progressUnits = if (indeterminate) 0 else ((content.progress01 ?: 0f) * max).roundToInt().coerceIn(0, max),
            progressMaxUnits = max,
        )
    }
    val units = content.segments.map { max(1, ceil(it.lengthM).toInt()) }
    val max = units.sum()
    val totalM = content.segments.sumOf { it.lengthM }.takeIf { it > 0.0 } ?: 1.0
    val scale = max / totalM
    val points = content.points.map { (it.positionM * scale).roundToInt().coerceIn(0, max) }
    val progress = if (indeterminate) {
        0
    } else {
        ((content.progress01 ?: 0f) * max).roundToInt().coerceIn(0, max)
    }
    return LiveUpdatePlan(
        content = content,
        style = liveUpdateStyleFor(sdkInt),
        indeterminate = indeterminate,
        segmentUnits = units,
        pointUnits = points,
        progressUnits = progress,
        progressMaxUnits = max,
    )
}

/**
 * `POST_NOTIFICATIONS` priming decision (plan W13-10: "primed in context").
 *
 * The request fires from the user gesture that starts the ongoing work —
 * Navigate start, Processing send — never at app launch. A refusal degrades
 * honestly (no notification; the screens keep working) and is never
 * re-asked from here: nagging from a navigation slice would be a dark
 * pattern, and the OS settings path stays available.
 */
enum class NotificationPermissionDecision {
    NOT_NEEDED,
    ALREADY_GRANTED,
    REQUEST_NOW,
    SKIP_ALREADY_ASKED,
}

fun notificationPermissionDecision(
    sdkInt: Int,
    isGranted: Boolean,
    alreadyAsked: Boolean,
): NotificationPermissionDecision = when {
    sdkInt < 33 -> NotificationPermissionDecision.NOT_NEEDED
    isGranted -> NotificationPermissionDecision.ALREADY_GRANTED
    !alreadyAsked -> NotificationPermissionDecision.REQUEST_NOW
    else -> NotificationPermissionDecision.SKIP_ALREADY_ASKED
}

/** Platform seam the Android sink implements; faked in host tests. */
interface LiveUpdateSink {
    fun show(notificationId: Int, plan: LiveUpdatePlan)
    fun dismiss(notificationId: Int)
}

/**
 * Version-branching director: pure apart from the injected [sink], so
 * host tests cover content, style branching, and dismiss rules with a
 * faked SDK int. Screens call `update*` on progress and [dismiss] when
 * navigation ends or processing settles.
 *
 * Navigation state changes at motion rate, so the director posts only when
 * the rendered plan differs from the last one it posted for that id: a
 * metre of progress is an update, twenty identical frames are not.
 * Arrival dismisses rather than leaving "0 m" ongoing.
 */
class LiveUpdateDirector(
    private val sink: LiveUpdateSink,
    private val sdkInt: Int,
) {
    private val lastPlans = mutableMapOf<Int, LiveUpdatePlan>()

    fun updateNav(
        notificationId: Int = NAV_LIVE_UPDATE_ID,
        ui: NavUi,
        legLengthsM: List<Double>,
        landmarks: List<LandmarkMark>,
        routeName: String,
    ) {
        if (ui.phase == NavPhase.ARRIVED) {
            dismiss(notificationId)
            return
        }
        show(notificationId, planLiveUpdate(liveUpdateForNav(ui, legLengthsM, landmarks, routeName), sdkInt))
    }

    fun updateProcessing(
        notificationId: Int = PROCESSING_LIVE_UPDATE_ID,
        phase: ProcessingPhase,
    ) {
        val content = liveUpdateForProcessing(phase)
        if (content == null) {
            dismiss(notificationId)
        } else {
            show(notificationId, planLiveUpdate(content, sdkInt))
        }
    }

    fun dismiss(notificationId: Int) {
        lastPlans.remove(notificationId)
        sink.dismiss(notificationId)
    }

    private fun show(notificationId: Int, plan: LiveUpdatePlan) {
        if (lastPlans[notificationId] == plan) return
        lastPlans[notificationId] = plan
        sink.show(notificationId, plan)
    }
}
