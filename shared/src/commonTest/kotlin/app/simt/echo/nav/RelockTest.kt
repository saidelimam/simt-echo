package app.simt.echo.nav

import app.simt.echo.Fixtures
import app.simt.echo.capture.visual.EgoMotionFit
import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.capture.visual.egoMotionTestFlow
import app.simt.echo.capture.visual.withParallax
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.nav.ghost.GhostFilter
import app.simt.echo.nav.ghost.Observation
import app.simt.echo.model.TurnDir
import app.simt.echo.nav.ghost.FingerprintHit
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.ui.screens.navigate.NavigateAction
import app.simt.echo.ui.screens.navigate.navigatePhasePresentation
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W12-06 gate: Lost → relock on the GHOST engine.
 *
 * A dropped follower re-spreads uniformly, then reconverges from the
 * turn signature (DTW), fingerprints and magnetic offsets: replay
 * scenarios relock within 10 m walked, a non-converging walk falls back
 * to today's OFF_ROUTE sheets honestly, and seeded runs replay
 * identically.
 */
class RelockTest {

    /** Mirrors ReplayMotionSource.sample mapping (heading q = 1, no mag). */
    private fun updatesOf(log: CaptureLog): List<MotionUpdate> =
        log.samples.mapIndexed { i, s ->
            MotionUpdate(
                tNs = s.tNs,
                distanceM = s.d,
                yawRad = s.yaw,
                stepCount = i + 1,
                isStep = i > 0,
                pressureHpa = s.p,
                source = "replay",
            )
        }

    private fun ghostNav(seed: Long = GhostFilter.DEFAULT_SEED): Navigator =
        Navigator(Fixtures.L_ROUTE, EchoConfig(navigatorEngine = NavigatorEngine.GHOST), seed)

    /** Walks [updates] until the odometer reaches [distM]; returns next index. */
    private fun feedUntil(nav: Navigator, updates: List<MotionUpdate>, from: Int, distM: Double): Int {
        var i = from
        while (i < updates.size && updates[i].distanceM < distM) {
            nav.onInput(NavInput.Motion(updates[i]))
            i++
        }
        return i
    }

    @Test
    fun lostRespreadsUniformThenRelocksFromTurnSignatureWithin10m() {
        val nav = ghostNav()
        val updates = updatesOf(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)))
        nav.onInput(NavInput.Motion(updates[0]))
        nav.onInput(NavInput.Aligned)
        var i = feedUntil(nav, updates, 1, 15.0)
        val lostAt = updates[i].distanceM

        nav.onInput(NavInput.Lost)
        assertEquals(NavPhase.RELOCKING, nav.ui.value.phase)
        assertEquals("Finding you…", nav.ui.value.instruction)
        assertTrue(
            nav.ui.value.uncertaintyM > 3.0,
            "re-spread σ should be wide, was ${nav.ui.value.uncertaintyM}",
        )

        var lockedAt = -1.0
        var foundCopy = ""
        while (i < updates.size) {
            nav.onInput(NavInput.Motion(updates[i]))
            if (nav.ui.value.phase == NavPhase.WALK) {
                lockedAt = updates[i].distanceM
                foundCopy = nav.ui.value.instruction
                break
            }
            assertTrue(
                updates[i].distanceM - lostAt <= 20.0,
                "walked past the 20 m fallback without OFF_ROUTE (phase=${nav.ui.value.phase})",
            )
            i++
        }
        assertTrue(lockedAt >= 0.0, "never relocked (phase=${nav.ui.value.phase}, σ=${nav.ui.value.uncertaintyM})")
        assertTrue(
            lockedAt - lostAt <= 10.0,
            "relocked after ${lockedAt - lostAt} m walked, budget is 10 m",
        )
        assertTrue(nav.ui.value.uncertaintyM < 3.0, "σ was ${nav.ui.value.uncertaintyM}")
        assertTrue(foundCopy.contains("Found you"), "copy was: $foundCopy")
        assertTrue(foundCopy.contains("Red pillar"), "copy was: $foundCopy")
    }

    @Test
    fun fingerprintHitAssistsRelockAndChipShows() {
        val nav = ghostNav()
        val updates = updatesOf(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)))
        nav.onInput(NavInput.Motion(updates[0]))
        nav.onInput(NavInput.Aligned)
        var i = feedUntil(nav, updates, 1, 18.0)
        val lostAt = updates[i].distanceM

        nav.onInput(NavInput.Lost)
        assertEquals(NavPhase.RELOCKING, nav.ui.value.phase)
        nav.onFingerprintHit(FingerprintHit(nodeS = 20.0, similarity01 = 0.95, label = "Red pillar"))
        assertEquals("That's the Red pillar?", nav.ui.value.fingerprintPrompt?.chipCopy())

        var lockedAt = -1.0
        while (i < updates.size) {
            nav.onInput(NavInput.Motion(updates[i]))
            if (nav.ui.value.phase == NavPhase.WALK) {
                lockedAt = updates[i].distanceM
                break
            }
            i++
        }
        assertTrue(lockedAt >= 0.0, "never relocked (phase=${nav.ui.value.phase}, σ=${nav.ui.value.uncertaintyM})")
        assertTrue(lockedAt - lostAt <= 10.0, "relocked after ${lockedAt - lostAt} m walked")
        assertTrue(nav.ui.value.instruction.contains("Found you"), "copy was: ${nav.ui.value.instruction}")
    }

    @Test
    fun nonConvergingWalkFallsBackToOffRouteSheets() {
        // Straight on at 0° forever: legs 0 and 2 share the heading, so a
        // re-spread cloud can never disambiguate — the honest outcome is
        // today's go-back / restart sheets after 20 m, never a fake lock.
        val nav = ghostNav()
        val updates = updatesOf(walkLog(listOf(25.0 to 0.0)))
        nav.onInput(NavInput.Motion(updates[0]))
        nav.onInput(NavInput.Aligned)
        var i = feedUntil(nav, updates, 1, 2.0)
        nav.onInput(NavInput.Lost)
        assertEquals(NavPhase.RELOCKING, nav.ui.value.phase)
        while (i < updates.size && nav.ui.value.phase == NavPhase.RELOCKING) {
            nav.onInput(NavInput.Motion(updates[i]))
            i++
        }
        assertEquals(NavPhase.OFF_ROUTE, nav.ui.value.phase)
        assertTrue(nav.ui.value.instruction.contains("Off route"), "copy was: ${nav.ui.value.instruction}")
        assertEquals(
            listOf(NavigateAction.GO_BACK_TO_LAST, NavigateAction.RESTART_FROM_START),
            navigatePhasePresentation(
                phase = nav.ui.value.phase,
                hasLandmark = false,
                isLastLeg = false,
                showCards = true,
            ).actions,
        )
    }

    @Test
    fun relockIsDeterministicForSeed() {
        fun run(): Triple<Double, Double, String> {
            val nav = ghostNav(seed = 1234L)
            val updates = updatesOf(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)))
            nav.onInput(NavInput.Motion(updates[0]))
            nav.onInput(NavInput.Aligned)
            var i = feedUntil(nav, updates, 1, 15.0)
            nav.onInput(NavInput.Lost)
            var lockedAt = -1.0
            while (i < updates.size) {
                nav.onInput(NavInput.Motion(updates[i]))
                if (nav.ui.value.phase == NavPhase.WALK) {
                    lockedAt = updates[i].distanceM
                    break
                }
                i++
            }
            return Triple(lockedAt, nav.ui.value.uncertaintyM, nav.ui.value.instruction)
        }
        val first = run()
        val second = run()
        assertTrue(first.first >= 0.0, "scenario never relocked")
        assertEquals(first, second)
    }

    @Test
    fun linearLostKeepsTodaySheetsImmediately() {
        val nav = Navigator(Fixtures.L_ROUTE, EchoConfig(navigatorEngine = NavigatorEngine.LINEAR))
        val updates = updatesOf(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0)))
        nav.onInput(NavInput.Motion(updates[0]))
        nav.onInput(NavInput.Aligned)
        feedUntil(nav, updates, 1, 10.0)
        nav.onInput(NavInput.Lost)
        assertEquals(NavPhase.OFF_ROUTE, nav.ui.value.phase)
        assertTrue(nav.ui.value.instruction.contains("Off route"))
    }

    @Test
    fun filterRespreadIsUniformAndSignaturePullsToTurn() {
        val filter = GhostFilter(Fixtures.L_ROUTE, seed = 5L)
        filter.respreadUniform()
        assertTrue(filter.meanS in 15.0..30.0, "uniform mean was ${filter.meanS}")
        assertTrue(filter.sigma > 10.0, "uniform σ was ${filter.sigma}")
        // Observed, ending at the walker's position: straight for four
        // metres, a right turn in the fifth, one more metre on. The L's
        // right turn is at s = 20, so the walker stands ≈ 22 m along (the
        // window looks backwards from s, never forward). One signature is
        // deliberately soft (σ = 0.6 rad), so the walk's successive windows
        // are modelled by repeating it until the evidence accumulates.
        val signature = doubleArrayOf(0.0, 0.0, 0.0, 0.0, PI / 2, 0.0)
        repeat(6) { filter.observe(Observation.TurnSignature(signature, 1.0)) }
        assertTrue(abs(filter.meanS - 22.0) <= 2.0, "post-signature mean was ${filter.meanS}")
    }

    @Test
    fun midRouteDropWithDepthProfileSignatureRelocksWithinSixMetres() {
        val totalBuckets = (Fixtures.L_ROUTE.totalLengthM / ParallaxProfile.STEP_M).toInt()
        val rhos = DoubleArray(totalBuckets) { b ->
            val s = b * ParallaxProfile.STEP_M
            // Junction opening signature between 16 m and 22 m.
            val drop = -0.16 * exp(-((s - 18.0) * (s - 18.0)) / 3.5)
            val rise = 0.14 * exp(-((s - 21.0) * (s - 21.0)) / 2.0)
            (0.25 + drop + rise).coerceAtLeast(0.05)
        }
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(totalBuckets) { ParallaxProfiler.quantiseRho(rhos[it]) }),
            trackability = Base64.encode(ByteArray(totalBuckets) { 220.toByte() }),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        val route = Fixtures.L_ROUTE.withParallax(profile)
        val nav = Navigator(
            route = route,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            ghostSeed = GhostFilter.DEFAULT_SEED,
        )
        val fitter = EgoMotionFit()
        val updates = updatesOf(walkLog(listOf(20.0 to 0.0, 15.0 to 90.0, 10.0 to 0.0)))
        nav.onInput(NavInput.Motion(updates[0]))
        nav.onInput(NavInput.Aligned)

        var i = 1
        while (i < updates.size && updates[i].distanceM < 15.0) {
            val u = updates[i]
            val b = (u.distanceM / ParallaxProfile.STEP_M).toInt().coerceIn(0, rhos.lastIndex)
            val ego = fitter.fit(egoMotionTestFlow(expansionPerSec = rhos[b]), usability01 = 0.90)
            nav.onInput(NavInput.Motion(u))
            nav.onVisualEgo(ego, usability01 = 0.90, nominalMps = 1.0)
            i++
        }
        val lostAt = updates[i].distanceM
        nav.onInput(NavInput.Lost)
        assertEquals(NavPhase.RELOCKING, nav.ui.value.phase)

        var lockedAt = -1.0
        while (i < updates.size) {
            val u = updates[i]
            val b = (u.distanceM / ParallaxProfile.STEP_M).toInt().coerceIn(0, rhos.lastIndex)
            val ego = fitter.fit(egoMotionTestFlow(expansionPerSec = rhos[b]), usability01 = 0.90)
            nav.onInput(NavInput.Motion(u))
            nav.onVisualEgo(ego, usability01 = 0.90, nominalMps = 1.0)
            if (nav.ui.value.phase == NavPhase.WALK) {
                lockedAt = u.distanceM
                break
            }
            i++
        }
        assertTrue(lockedAt >= 0.0, "never relocked")
        assertTrue(
            lockedAt - lostAt <= 6.0,
            "mid-route drop with depth signature must relock within 6 m, took ${lockedAt - lostAt} m",
        )
    }
}
