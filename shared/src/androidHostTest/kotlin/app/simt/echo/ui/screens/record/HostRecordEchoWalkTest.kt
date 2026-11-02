package app.simt.echo.ui.screens.record

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.simt.echo.capture.CaptureHud
import app.simt.echo.capture.CaptureState
import app.simt.echo.capture.FrameSample
import app.simt.echo.capture.GlimpseLedger
import app.simt.echo.capture.LedgerOffer
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.atmosphere.CosmosMotionTier
import app.simt.echo.ui.atmosphere.ProvideCosmosMotionTier
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.PI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W13-04 Record "Echo walk" gate: step ripples, trail ribbon, node blooms,
 * gold brackets and rolling digits — every element bound to its named
 * signal (see the mapping table on [EchoWalkState]). Screenshots are N/A
 * in CI, so the tags asserted here are the V-review hooks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class HostRecordEchoWalkTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    private var now = 1_000_000L

    private fun tracker() = EchoWalkTracker(nowMs = { now })

    private fun update(
        tMs: Long,
        distanceM: Double,
        yawRad: Double,
        isStep: Boolean,
        stepCount: Int,
    ) = MotionUpdate(
        tNs = tMs * 1_000_000L,
        distanceM = distanceM,
        yawRad = yawRad,
        stepCount = stepCount,
        isStep = isStep,
        pressureHpa = null,
        source = "test",
    )

    /** Straight walk then a settled 90° right turn (fires the online detector once). */
    private fun walkWithTurn(tracker: EchoWalkTracker): EchoWalkSnapshot {
        var d = 0.0
        var steps = 0
        for (t in 0L..5000L step 100L) {
            val step = t % 1000L == 0L
            if (step) {
                steps++
                d += 0.7
            }
            tracker.onMotion(update(t, d, 0.0, step, steps))
        }
        for (t in 5100L..7000L step 100L) {
            val step = t % 1000L == 0L
            if (step) {
                steps++
                d += 0.7
            }
            tracker.onMotion(update(t, d, PI / 2, step, steps))
        }
        return tracker.snapshot()
    }

    // Steps → ripples.

    @Test
    fun stepRipple_alternatesEdgeBandsFullOnly() {
        val tracker = tracker()
        tracker.onMotion(update(0L, 0.7, 0.0, true, 1))
        tracker.onMotion(update(600L, 1.4, 0.0, true, 2))
        val ripples = tracker.snapshot().ripples
        assertEquals(2, ripples.size)
        assertTrue(ripples[0].leftEdge != ripples[1].leftEdge, "footsteps must alternate edges")
        for (ripple in ripples) {
            val x = ripple.xFraction()
            assertTrue(x < 0.2f || x > 0.8f, "origin $x must stay out of the centre 60 %")
            // The ring itself is confined to its band, however far it travels.
            val band = rippleBand(ripple.leftEdge, width = 1000f)
            assertTrue(ripple.xFraction() * 1000f in band, "origin must sit inside its own band")
            assertTrue(band.start >= 800f || band.endInclusive <= 200f, "band $band must exclude the centre 60 %")
        }
        assertTrue(stepRipplesAlive(ripples, now), "fresh ripples travel")
        assertFalse(stepRipplesAlive(ripples, now + 900L), "ripples die after the 900 ms motif")
    }

    @Test
    fun stepRipple_rendersOnFullTierOnly() {
        val tracker = tracker()
        tracker.onMotion(update(0L, 0.7, 0.0, true, 1))
        val ripples = tracker.snapshot().ripples
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.FULL) {
                    StepRipples(ripples = ripples, nowMs = { now + 100L })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(StepRipplesTag).assertIsDisplayed()
    }

    @Test
    fun stepRipple_absentBelowFullTier() {
        val tracker = tracker()
        tracker.onMotion(update(0L, 0.7, 0.0, true, 1))
        val ripples = tracker.snapshot().ripples
        var tier by mutableStateOf(CosmosMotionTier.REDUCED)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = tier) {
                    StepRipples(ripples = ripples, nowMs = { now + 100L })
                }
            }
        }
        for (next in listOf(CosmosMotionTier.REDUCED, CosmosMotionTier.STATIC)) {
            compose.runOnIdle { tier = next }
            compose.waitForIdle()
            compose.onNodeWithTag(StepRipplesTag).assertDoesNotExist()
        }
    }

    // Trail + blooms.

    @Test
    fun trailBloom_turnAndMarkNodes() {
        val tracker = tracker()
        val snapshot = walkWithTurn(tracker)
        val turns = snapshot.trail.filter { it.node == TrailNodeKind.TURN }
        assertEquals(1, turns.size, "the settled 90° turn must bloom exactly one node")
        tracker.onMarks(1)
        val marks = tracker.snapshot().trail.filter { it.node == TrailNodeKind.MARK }
        assertEquals(1, marks.size, "a mark annotates the trail")
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.FULL) {
                    TrailRibbon(trail = tracker.snapshot().trail, headYaw = PI / 2)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(TrailRibbonTag).assertIsDisplayed()
        compose.onNodeWithTag(TrailRibbonTag).assertContentDescriptionEquals("Walked trail: 1 turns, 1 spots")
        compose.onNodeWithTag("$TrailNodeTagPrefix:TURN:${turns.single().id}").assertIsDisplayed()
        compose.onNodeWithTag("$TrailNodeTagPrefix:MARK:${marks.single().id}").assertIsDisplayed()
    }

    @Test
    fun trailBloom_keepsItsNodeWhenAnOlderNodeIsTrimmed() {
        val trail = listOf(
            TrailPoint(id = 0, distanceM = 0.0, yawRad = 0.0),
            TrailPoint(id = 1, distanceM = 1.0, yawRad = 0.0, node = TrailNodeKind.TURN),
            TrailPoint(id = 2, distanceM = 2.0, yawRad = 0.0),
            TrailPoint(id = 3, distanceM = 3.0, yawRad = 0.0, node = TrailNodeKind.MARK),
        )
        var shown by mutableStateOf(trail)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.STATIC) {
                    TrailRibbon(trail = shown, headYaw = 0.0)
                }
            }
        }
        compose.waitForIdle()
        val markTag = "$TrailNodeTagPrefix:MARK:3"
        val before = compose.onNodeWithTag(markTag).fetchSemanticsNode().id

        // The trail trims its oldest points: the TURN node leaves, indices shift.
        compose.runOnIdle { shown = trail.drop(2) }
        compose.waitForIdle()

        compose.onNodeWithTag("$TrailNodeTagPrefix:TURN:1").assertDoesNotExist()
        val after = compose.onNodeWithTag(markTag).fetchSemanticsNode().id
        assertEquals(before, after, "the surviving node must keep its composition, not inherit a slot and re-bloom")
    }

    @Test
    fun ribbon_headingUpProjectsPastBelow() {
        val points = listOf(
            TrailPoint(id = 0, distanceM = 0.0, yawRad = 0.0),
            TrailPoint(id = 1, distanceM = 1.0, yawRad = 0.0),
            TrailPoint(id = 2, distanceM = 2.0, yawRad = 0.0),
        )
        val northUp = projectTrail(points, headYaw = 0.0)
        assertEquals(listOf(0.0, 0.0, 0.0), northUp.map { it.lateralM })
        assertTrue(northUp[0].behindM > northUp[2].behindM, "older points fall further below the head")
        assertEquals(0.0, northUp[2].behindM, 1e-9)
        // Head turns east: the northern past now lies to the right, none of it ahead.
        // (The head itself sits at the origin by construction.)
        val eastUp = projectTrail(points, headYaw = PI / 2)
        assertTrue(eastUp.dropLast(1).all { it.lateralM > 0.0 }, "facing east, the northern trail sits right")
        assertTrue(eastUp.all { it.behindM >= -1e-9 }, "heading-up never puts the past ahead")
    }

    @Test
    fun staticTier_settlesInstantlyWithSameFinalState() {
        val tracker = tracker()
        walkWithTurn(tracker)
        tracker.onMarks(1)
        val trail = tracker.snapshot().trail
        val turnId = trail.first { it.node == TrailNodeKind.TURN }.id
        val highlight = LedgerHighlight(score = 0.95, novelty = 0.5, atMs = now)
        var tier by mutableStateOf(CosmosMotionTier.FULL)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = tier) {
                    TrailRibbon(trail = trail, headYaw = PI / 2)
                    LedgerBrackets(highlight = highlight)
                }
            }
        }
        for (next in listOf(CosmosMotionTier.FULL, CosmosMotionTier.STATIC)) {
            compose.runOnIdle { tier = next }
            compose.waitForIdle()
            compose.onNodeWithTag("$TrailNodeTagPrefix:TURN:$turnId").assertIsDisplayed()
            compose.onNodeWithTag(LedgerBracketsTag).assertIsDisplayed()
            compose.onNodeWithText(LEDGER_BRACKET_COPY).assertIsDisplayed()
        }
    }

    // Ledger brackets.

    @Test
    fun brackets_fireOnlyForTopDecileNovelKeeps() {
        val tracker = tracker()
        for (i in 1..9) {
            tracker.onLedgerOffer(LedgerOffer.Kept(bucketIndex = i.toLong(), score = i / 10.0, novelty = 1.0))
            assertNull(tracker.snapshot().highlight, "history below 9 priors can never be top-decile")
        }
        tracker.onLedgerOffer(LedgerOffer.Dropped(bucketIndex = 1, score = 0.99, novelty = 1.0, keptScore = 0.1))
        assertNull(tracker.snapshot().highlight, "dropped frames never bracket, however sharp")
        tracker.onLedgerOffer(LedgerOffer.Kept(bucketIndex = 10, score = 0.95, novelty = 0.0))
        assertNull(tracker.snapshot().highlight, "a familiar view never brackets, however sharp")
        tracker.onLedgerOffer(LedgerOffer.Replaced(bucketIndex = 2, score = 0.97, novelty = 0.5, oldScore = 0.2))
        val highlight = tracker.snapshot().highlight
        assertNotNull(highlight, "a novel top-decile replacement must bracket")
        assertEquals(0.97, highlight.score, 1e-9)
        assertTrue(isBracketOpen(highlight, now), "the window opens at once")
        assertFalse(isBracketOpen(highlight, now + LEDGER_BRACKET_MS), "the window closes after 300 ms")
        assertFalse(
            isTopDecileAndNovel(0.97, 0.5, List(9) { 0.99 }),
            "below 90 % of priors is not top-decile",
        )
    }

    @Test
    fun brackets_followTheSessionLedgerFeed() {
        val tracker = tracker()
        val offers = (1..9).map { LedgerOffer.Kept(bucketIndex = it.toLong(), score = it / 10.0, novelty = 1.0) } +
            LedgerOffer.Replaced(bucketIndex = 2, score = 0.97, novelty = 0.5, oldScore = 0.2)
        var refreshes = 0

        runBlocking { tracker.collectLedgerOffers(offers.asFlow()) { refreshes++ } }

        assertEquals(offers.size, refreshes, "the screen refreshes its snapshot once per offer")
        assertEquals(0.97, assertNotNull(tracker.snapshot().highlight).score, 1e-9)
    }

    @Test
    fun brackets_showHonestCopyThenExpire() {
        var expired = false
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.FULL) {
                    LedgerBrackets(
                        highlight = LedgerHighlight(score = 0.97, novelty = 0.5, atMs = now),
                        onExpired = { expired = true },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(LedgerBracketsTag).assertIsDisplayed()
        compose.onNodeWithTag(LedgerBracketsTag).assertContentDescriptionEquals(LEDGER_BRACKET_COPY)
        // "Announced" means a polite live region, not just a description.
        compose.onNodeWithTag(LedgerBracketsTag)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(LEDGER_BRACKET_COPY).assertIsDisplayed()
        compose.waitUntil(timeoutMillis = 5_000) { expired }
    }

    @Test
    fun ledgerOffer_carriesNoveltyThroughTheRealLedger() {
        val ledger = GlimpseLedger()
        fun sample(luma: ByteArray, tNs: Long) = FrameSample(
            tNs = tNs,
            width = 32,
            height = 32,
            decimation = 1,
            luma = luma,
        )
        val sharp = ByteArray(32 * 32) { i -> (if ((i / 4) % 2 == 0) 200 else 40).toByte() }
        val first = ledger.offer(sample(sharp, 1L), odometerM = 0.1)
        assertTrue(first is LedgerOffer.Kept)
        assertEquals(1.0, first.novelty, 1e-12)
        val flat = ByteArray(32 * 32) { 128.toByte() }
        val second = ledger.offer(sample(flat, 2L), odometerM = 0.6)
        assertTrue(second is LedgerOffer.Kept, "an empty bucket keeps coverage, was $second")
        assertTrue(second.novelty in 0.0..1.0, "novelty rides every offer, was ${second.novelty}")
    }

    // Rolling digits + instrument wiring.

    @Test
    fun rollingDigits_announceWholeValueEveryTier() {
        var tier by mutableStateOf(CosmosMotionTier.FULL)
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = tier) {
                    RollingDistance(text = "23.4 m")
                }
            }
        }
        for (next in CosmosMotionTier.entries) {
            compose.runOnIdle { tier = next }
            compose.waitForIdle()
            compose.onNodeWithTag(RollingDistanceTag).assertIsDisplayed()
            compose.onNodeWithTag(RollingDistanceTag).assertContentDescriptionEquals("Distance 23.4 m")
            // One focusable node for the whole value: digits are drawn, never separately focusable.
            compose.onNodeWithTag(RollingDistanceTag).onChildren().assertCountEquals(0)
            compose.onNodeWithText("2", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("m", useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test
    fun instrument_showsEchoWalkWithHeadingKept() {
        val tracker = tracker()
        walkWithTurn(tracker)
        tracker.onMarks(1)
        for (i in 1..9) {
            tracker.onLedgerOffer(LedgerOffer.Kept(i.toLong(), i / 10.0, 1.0))
        }
        tracker.onLedgerOffer(LedgerOffer.Kept(10, 0.97, 0.5))
        val walk = tracker.snapshot()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ProvideCosmosMotionTier(tier = CosmosMotionTier.FULL) {
                    RecordInstrument(
                        state = CaptureState.RECORDING,
                        hud = CaptureHud(distanceM = 23.4, steps = 31, elapsedMs = 65_000L, marks = 1),
                        headingRad = PI / 2,
                        error = null,
                        onStart = {},
                        onMark = {},
                        onBeginFinish = {},
                        onSave = { _, _ -> },
                        walk = walk,
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(StepRipplesTag).assertIsDisplayed()
        compose.onNodeWithTag(TrailRibbonTag).assertIsDisplayed()
        compose.onNodeWithTag(LedgerBracketsTag).assertIsDisplayed()
        compose.onNodeWithText(LEDGER_BRACKET_COPY).assertIsDisplayed()
        compose.onNodeWithTag(RollingDistanceTag)
            .assertContentDescriptionEquals("Distance 23.4 m")
        compose.onNodeWithText("90° E").assertIsDisplayed()
        compose.onNodeWithText("Mark spot").assertIsDisplayed()
    }
}
