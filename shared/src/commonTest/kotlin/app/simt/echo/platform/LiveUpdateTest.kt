package app.simt.echo.platform

import app.simt.echo.model.TurnDir
import app.simt.echo.nav.NavPhase
import app.simt.echo.nav.NavUi
import app.simt.echo.transfer.JobStage
import app.simt.echo.ui.screens.processing.ConsentInfo
import app.simt.echo.ui.screens.processing.ProcessingPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan W13-10 acceptance: notification-builder unit tests — content
 * (segments/points/text), version branching via faked SDK int, and
 * permission-priming logic. All host-testable through the pure content
 * model plus [LiveUpdateDirector] with an injected fake sink.
 */
class LiveUpdateTest {

    private fun walkUi(
        legIndex: Int = 1,
        progress01: Double = 0.5,
        instruction: String = "Turn left in 12 m",
    ) = NavUi(
        phase = NavPhase.WALK,
        legIndex = legIndex,
        instruction = instruction,
        distanceToNextM = 12.0,
        arrowRad = 0.1,
        nextTurn = TurnDir.LEFT,
        landmark = null,
        uncertaintyM = 1.0,
        progress01 = progress01,
    )

    private class FakeSink : LiveUpdateSink {
        val shown = mutableListOf<Pair<Int, LiveUpdatePlan>>()
        val dismissed = mutableListOf<Int>()
        override fun show(notificationId: Int, plan: LiveUpdatePlan) {
            shown += notificationId to plan
        }
        override fun dismiss(notificationId: Int) {
            dismissed += notificationId
        }
    }

    @Test
    fun navSegmentsMarkLegsDoneAndCurrent() {
        val content = liveUpdateForNav(
            walkUi(legIndex = 1),
            legLengthsM = listOf(10.0, 20.0, 15.0),
            landmarks = emptyList(),
            routeName = "Evening loop",
        )
        assertEquals(
            listOf(
                LiveUpdateSegment(10.0, done = true, current = false),
                LiveUpdateSegment(20.0, done = false, current = true),
                LiveUpdateSegment(15.0, done = false, current = false),
            ),
            content.segments,
        )
    }

    @Test
    fun navPointsCarryTitlesButNeverPhotos() {
        val content = liveUpdateForNav(
            walkUi(),
            legLengthsM = listOf(10.0, 20.0),
            landmarks = listOf(
                LandmarkMark(positionM = 10.0, title = "Red door"),
                LandmarkMark(positionM = 30.0, title = "Corner shop"),
            ),
            routeName = "Evening loop",
        )
        assertEquals(
            listOf(
                LiveUpdatePoint(10.0, "Red door"),
                LiveUpdatePoint(30.0, "Corner shop"),
            ),
            content.points,
        )
    }

    @Test
    fun navTextPassesNavigatorInstructionVerbatim() {
        val content = liveUpdateForNav(
            walkUi(instruction = "Turn left in 12 m"),
            legLengthsM = listOf(10.0, 20.0),
            landmarks = emptyList(),
            routeName = "Evening loop",
        )
        assertEquals("Evening loop", content.title)
        assertEquals("Turn left in 12 m", content.text)
        assertTrue(content.ongoing)
    }

    @Test
    fun navProgressAggregatesCompletedLegs() {
        val content = liveUpdateForNav(
            walkUi(legIndex = 1, progress01 = 0.5),
            legLengthsM = listOf(10.0, 10.0),
            landmarks = emptyList(),
            routeName = "Evening loop",
        )
        assertEquals(0.75f, content.progress01)
    }

    @Test
    fun processingUploadingIsIndeterminate() {
        val content = liveUpdateForProcessing(ProcessingPhase.Uploading)
        assertTrue(content != null)
        assertEquals("Uploading…", content.text)
        assertTrue(content.segments.isEmpty())
        assertNull(content.progress01)
        val plan = planLiveUpdate(content, 36)
        assertTrue(plan.indeterminate)
        assertEquals(LiveUpdateStyle.PROGRESS, plan.style)
    }

    @Test
    fun processingWorkingMapsStageFractions() {
        val expected = mapOf(
            JobStage.QUEUED to 0.05f,
            JobStage.READING to 0.25f,
            JobStage.SCOUTING to 0.5f,
            JobStage.GROUNDING to 0.75f,
        )
        for ((stage, fraction) in expected) {
            val content = liveUpdateForProcessing(ProcessingPhase.Working(stage))
            assertTrue(content != null, "$stage should stay ongoing")
            assertEquals(fraction, content.progress01, "$stage fraction changed")
        }
    }

    @Test
    fun processingSettledPhasesReturnNull() {
        assertNull(liveUpdateForProcessing(ProcessingPhase.Ready))
        assertNull(liveUpdateForProcessing(ProcessingPhase.Failed("boom")))
        assertNull(liveUpdateForProcessing(ProcessingPhase.AiUnavailable))
        assertNull(
            liveUpdateForProcessing(
                ProcessingPhase.Consent(ConsentInfo(0, 0, false), hasJob = false),
            ),
        )
    }

    @Test
    fun directorDismissesWhenProcessingSettles() {
        val sink = FakeSink()
        LiveUpdateDirector(sink, 36).updateProcessing(phase = ProcessingPhase.Ready)
        assertEquals(listOf(PROCESSING_LIVE_UPDATE_ID), sink.dismissed)
        assertTrue(sink.shown.isEmpty())
    }

    @Test
    fun versionBranchingUsesProgressOn16AndLegacyBelow() {
        assertEquals(LiveUpdateStyle.PROGRESS, liveUpdateStyleFor(36))
        assertEquals(LiveUpdateStyle.LEGACY_ONGOING, liveUpdateStyleFor(35))
        assertEquals(LiveUpdateStyle.LEGACY_ONGOING, liveUpdateStyleFor(34))
        assertEquals(LiveUpdateStyle.LEGACY_ONGOING, liveUpdateStyleFor(29))
    }

    @Test
    fun directorBranchesStyleByFakedSdkInt() {
        val newSink = FakeSink()
        LiveUpdateDirector(newSink, 36).updateNav(
            ui = walkUi(),
            legLengthsM = listOf(10.0, 20.0),
            landmarks = emptyList(),
            routeName = "Evening loop",
        )
        val oldSink = FakeSink()
        LiveUpdateDirector(oldSink, 34).updateNav(
            ui = walkUi(),
            legLengthsM = listOf(10.0, 20.0),
            landmarks = emptyList(),
            routeName = "Evening loop",
        )
        assertEquals(LiveUpdateStyle.PROGRESS, newSink.shown.single().second.style)
        assertEquals(LiveUpdateStyle.LEGACY_ONGOING, oldSink.shown.single().second.style)
        assertEquals(
            newSink.shown.single().second.content,
            oldSink.shown.single().second.content,
            "style must not change content",
        )
    }

    @Test
    fun segmentUnitsCoverZeroLengthLegs() {
        val plan = planLiveUpdate(
            liveUpdateForNav(
                walkUi(legIndex = 0, progress01 = 0.0),
                legLengthsM = listOf(0.0, 20.4),
                landmarks = listOf(LandmarkMark(20.4, "Gate")),
                routeName = "Evening loop",
            ),
            36,
        )
        assertEquals(listOf(1, 21), plan.segmentUnits)
        assertEquals(listOf(22), plan.pointUnits)
        assertEquals(22, plan.progressMaxUnits)
        assertEquals(0, plan.progressUnits)
    }

    @Test
    fun lockScreenTextNamesNeitherTheRouteNorTheInstruction() {
        val ui = walkUi(instruction = "Turn left at the Red door in 12 m")
        val content = liveUpdateForNav(
            ui,
            legLengthsM = listOf(10.0, 20.0),
            landmarks = listOf(LandmarkMark(10.0, "Red door")),
            routeName = "Evening loop",
        )
        assertEquals("Navigating", content.publicText)
        assertTrue(!content.publicText.contains("Evening loop"))
        assertTrue(!content.publicText.contains("Red door"))
        // Processing keeps its own, equally generic, line.
        assertEquals(
            LIVE_UPDATE_PUBLIC_PROCESSING_TEXT,
            liveUpdateForProcessing(ProcessingPhase.Uploading)?.publicText,
        )
    }

    @Test
    fun directorPostsAChangeOnceNotEveryFrame() {
        val sink = FakeSink()
        val director = LiveUpdateDirector(sink, 36)
        fun post(ui: NavUi) = director.updateNav(
            ui = ui, legLengthsM = listOf(10.0, 20.0), landmarks = emptyList(), routeName = "Loop",
        )
        repeat(20) { post(walkUi(progress01 = 0.5)) }
        assertEquals(1, sink.shown.size, "identical frames must not re-post")
        post(walkUi(progress01 = 0.6, instruction = "Turn left in 8 m"))
        assertEquals(2, sink.shown.size)
    }

    @Test
    fun arrivalDismissesTheOngoingNotification() {
        val sink = FakeSink()
        val director = LiveUpdateDirector(sink, 36)
        director.updateNav(ui = walkUi(), legLengthsM = listOf(10.0), landmarks = emptyList(), routeName = "Loop")
        director.updateNav(
            ui = walkUi().copy(phase = NavPhase.ARRIVED),
            legLengthsM = listOf(10.0),
            landmarks = emptyList(),
            routeName = "Loop",
        )
        assertEquals(listOf(NAV_LIVE_UPDATE_ID), sink.dismissed)
        assertEquals(1, sink.shown.size)
        // A fresh walk posts again after a dismiss.
        director.updateNav(ui = walkUi(), legLengthsM = listOf(10.0), landmarks = emptyList(), routeName = "Loop")
        assertEquals(2, sink.shown.size)
    }

    @Test
    fun permissionPrimingAsksOnceInContext() {
        assertEquals(
            NotificationPermissionDecision.NOT_NEEDED,
            notificationPermissionDecision(sdkInt = 29, isGranted = false, alreadyAsked = false),
        )
        assertEquals(
            NotificationPermissionDecision.NOT_NEEDED,
            notificationPermissionDecision(sdkInt = 32, isGranted = false, alreadyAsked = false),
        )
        assertEquals(
            NotificationPermissionDecision.ALREADY_GRANTED,
            notificationPermissionDecision(sdkInt = 34, isGranted = true, alreadyAsked = true),
        )
        assertEquals(
            NotificationPermissionDecision.REQUEST_NOW,
            notificationPermissionDecision(sdkInt = 34, isGranted = false, alreadyAsked = false),
        )
        assertEquals(
            NotificationPermissionDecision.SKIP_ALREADY_ASKED,
            notificationPermissionDecision(sdkInt = 36, isGranted = false, alreadyAsked = true),
        )
    }
}
