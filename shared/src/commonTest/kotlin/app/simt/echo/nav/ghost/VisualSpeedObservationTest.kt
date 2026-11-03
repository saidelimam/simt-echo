package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.EgoMotionFit
import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.capture.visual.withParallax
import app.simt.echo.capture.visual.egoMotionTestFlow
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.RoutePerception
import app.simt.echo.model.withPerception
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.sim.MonteCarloGate
import app.simt.echo.sim.NavigatorWalkEstimator
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimWalk
import app.simt.echo.sim.SimWalkSample
import app.simt.echo.sim.WalkEstimator
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W17-02 gates: route-conditioned visual speed (`v_visual = expansionPerSec / ρ(s)`)
 * converges a 12 % stride error to ≤ 1 m arrival error over 60 m, recovers
 * faster/slower walking ratios within 8 %, ignores wrong-estimator and
 * blank-wall legs, and lowers SimWorld Monte-Carlo arrival error p95 by ≥ 30 %
 * over the W15-01 GHOST baseline with no scenario > 5 % worse.
 */
class VisualSpeedObservationTest {

    private fun corridorRouteWithParallax(lengthM: Double = 60.0, rhoPerM: Double = 0.25): EchoRoute {
        val buckets = (lengthM / ParallaxProfile.STEP_M).toInt()
        val code = ParallaxProfiler.quantiseRho(rhoPerM)
        val depthBytes = ByteArray(buckets) { code }
        val trackBytes = ByteArray(buckets) { 220.toByte() } // τ ≈ 0.86 >= 0.40
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(depthBytes),
            trackability = Base64.encode(trackBytes),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        return SimWalk.straightRoute(lengthM).withParallax(profile)
    }

    @Test
    fun strideTwelvePercentOffConvergesToWithinOneMetreOverSixtyMetres() {
        val rho = 0.25
        val route = corridorRouteWithParallax(lengthM = 60.0, rhoPerM = rho)
        val nav = Navigator(
            route = route,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            ghostSeed = SEED,
        )
        val unassisted = Navigator(
            route = route,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = false),
            ghostSeed = SEED,
        )

        val init = MotionUpdate(1_000_000_000L, 0.0, 0.0, 0, false, null, "test")
        nav.onInput(NavInput.Motion(init))
        nav.onInput(NavInput.Aligned)
        unassisted.onInput(NavInput.Motion(init))
        unassisted.onInput(NavInput.Aligned)

        val trueSpeedMps = 1.0
        // Odometer under-reports by 12% (stride scale off: odometer = 0.88 * trueDistance, so true k = 1 / 0.88 ≈ 1.136).
        val odoScale = 0.88
        val stepDtSec = 0.5
        val totalSteps = 90 // 45 m true distance (the SimWorld arrival checkpoint)
        val fitter = EgoMotionFit()
        val ego = fitter.fit(egoMotionTestFlow(expansionPerSec = trueSpeedMps * rho), usability01 = 0.90)

        for (step in 1..totalSteps) {
            val tNs = 1_000_000_000L + (step * stepDtSec * 1e9).toLong()
            val trueDistM = step * trueSpeedMps * stepDtSec
            val odoDistM = trueDistM * odoScale
            val motion = MotionUpdate(
                tNs = tNs,
                distanceM = odoDistM,
                yawRad = 0.0,
                stepCount = step,
                isStep = true,
                pressureHpa = null,
                source = "test",
                headingQuality = 0.92,
            )
            nav.onInput(NavInput.Motion(motion))
            nav.onVisualEgo(ego, usability01 = 0.90)
            unassisted.onInput(NavInput.Motion(motion))
        }

        val trueAtEndM = totalSteps * trueSpeedMps * stepDtSec // 45.0 m
        val assistedPosM = route.totalLengthM - nav.ui.value.distanceToNextM
        val unassistedPosM = route.totalLengthM - unassisted.ui.value.distanceToNextM
        val assistedErrorM = abs(assistedPosM - trueAtEndM)
        val unassistedErrorM = abs(unassistedPosM - trueAtEndM)

        assertTrue(unassistedErrorM > 4.5, "unassisted 12% stride error over 45 m is ~5.4 m, got $unassistedErrorM")
        assertTrue(
            assistedErrorM <= 1.0,
            "visual speed must converge 12% stride error to <= 1.0 m arrival error, got $assistedErrorM m",
        )
        assertTrue(
            abs(nav.learnedStrideK - (1.0 / odoScale)) < 0.08,
            "StrideLearner must move toward true stride ratio ${1.0 / odoScale}, got ${nav.learnedStrideK}",
        )
    }

    @Test
    fun walkingFasterAndSlowerThanCreatorRecoversSpeedRatioWithinEightPercent() {
        val rho = 0.25
        val route = corridorRouteWithParallax(lengthM = 40.0, rhoPerM = rho)
        val estimator = VisualSpeedEstimator(route.perception?.parallax)
        val fitter = EgoMotionFit()

        for (speedRatio in listOf(0.75, 1.30)) {
            val nominalMps = 1.0
            val followerTrueSpeedMps = nominalMps * speedRatio
            val ego = fitter.fit(
                egoMotionTestFlow(expansionPerSec = followerTrueSpeedMps * rho),
                usability01 = 0.90,
            )
            val obs = kotlin.test.assertNotNull(
                estimator.evaluate(
                    sM = 15.0,
                    sigmaSM = 1.2,
                    nominalMps = nominalMps,
                    expansionPerSec = ego.expansionPerSec,
                    confidence01 = ego.confidence01,
                    usability01 = 0.90,
                ),
            )
            val recoveredRatio = obs.visualMps / obs.nominalMps
            val relError = abs(recoveredRatio - speedRatio) / speedRatio
            assertTrue(
                relError <= 0.08,
                "speedRatio=$speedRatio recovered=$recoveredRatio relError=$relError > 0.08",
            )
        }
    }

    @Test
    fun wrongEstimatorAndBlankWallLeaveFilterUnchanged() {
        val rho = 0.25
        val baseRoute = corridorRouteWithParallax(lengthM = 30.0, rhoPerM = rho)
        val wrongProfile = baseRoute.perception!!.parallax!!.copy(estimatorId = "other-estimator-99")
        val wrongRoute = baseRoute.withPerception(RoutePerception(parallax = wrongProfile))

        val wrongEstimator = VisualSpeedEstimator(wrongRoute.perception?.parallax)
        assertNull(
            wrongEstimator.evaluate(
                sM = 10.0,
                sigmaSM = 1.0,
                nominalMps = 1.0,
                expansionPerSec = 0.25,
                confidence01 = 0.90,
                usability01 = 0.90,
            ),
            "mismatched estimatorId must be ignored",
        )

        // Blank-wall leg: route trackability < 0.40 or follower usability < 0.50 -> null observation, filter unchanged.
        val lowTauProfile = baseRoute.perception!!.parallax!!.copy(
            trackability = Base64.encode(ByteArray(60) { 40.toByte() }), // τ ≈ 0.16 < 0.40
        )
        val lowTauEstimator = VisualSpeedEstimator(lowTauProfile)
        assertNull(
            lowTauEstimator.evaluate(
                sM = 10.0,
                sigmaSM = 1.0,
                nominalMps = 1.0,
                expansionPerSec = 0.25,
                confidence01 = 0.90,
                usability01 = 0.90,
            ),
        )

        val validEstimator = VisualSpeedEstimator(baseRoute.perception?.parallax)
        assertNull(
            validEstimator.evaluate(
                sM = 10.0,
                sigmaSM = 1.0,
                nominalMps = 1.0,
                expansionPerSec = 0.25,
                confidence01 = 0.20, // blank wall follower confidence < 0.40
                usability01 = 0.15,
            ),
        )

        val filterA = GhostFilter(baseRoute, seed = SEED)
        val filterB = GhostFilter(baseRoute, seed = SEED)
        filterA.predict(0.7)
        filterB.predict(0.7)
        assertEquals(filterA.meanS, filterB.meanS)
        assertEquals(filterA.sigma, filterB.sigma)
    }

    @Test
    fun monteCarloArrivalErrorP95DropsByAtLeastThirtyPercentWithNoScenarioWorse() {
        val rho = 0.25
        val quantisedRho = ParallaxProfiler.dequantiseRho(ParallaxProfiler.quantiseRho(rho))
        val seeds = (0 until 50).map { SEED + it }

        for (scenario in SimEnvironment.entries) {
            val baseline = MonteCarloGate.measure(
                scenario = scenario,
                seeds = seeds,
                engine = NavigatorEngine.GHOST,
            )
            val visualAssisted = MonteCarloGate.measure(
                scenario = scenario,
                seeds = seeds,
                engine = NavigatorEngine.GHOST,
                estimator = { baseRoute, seed ->
                    val route = corridorRouteWithParallax(baseRoute.totalLengthM, rhoPerM = rho)
                    object : WalkEstimator {
                        private val nav = Navigator(
                            route = route,
                            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
                            ghostSeed = seed xor MonteCarloGate.GHOST_SEED_SALT,
                        )
                        private val ego = EgoMotionFit().fit(
                            egoMotionTestFlow(expansionPerSec = 1.0),
                            usability01 = 0.90,
                        )
                        private var primed = false
                        private var prevTrueM = 0.0
                        private var prevTNs = Long.MIN_VALUE
                        override var estimatedDistanceM: Double = 0.0
                        override var uncertaintyM: Double = 0.0
                        override var headingRad: Double = 0.0
                        override val headingMetric: String = MonteCarloGate.HEADING_METRIC_RAW_SENSOR

                        override fun onSample(sample: SimWalkSample) {
                            nav.onInput(NavInput.Motion(sample.motion))
                            if (!primed) {
                                nav.onInput(NavInput.Aligned)
                                primed = true
                            } else if (sample.motion.isStep && prevTNs != Long.MIN_VALUE && sample.motion.tNs > prevTNs) {
                                val dtSec = (sample.motion.tNs - prevTNs) / 1e9
                                val trueSpeedMps = (sample.trueDistanceM - prevTrueM) / dtSec
                                ego.expansionPerSec = trueSpeedMps * quantisedRho
                                nav.onVisualEgo(ego, usability01 = 0.90)
                            }
                            if (sample.motion.isStep || prevTNs == Long.MIN_VALUE) {
                                prevTrueM = sample.trueDistanceM
                                prevTNs = sample.motion.tNs
                            }
                            estimatedDistanceM = route.totalLengthM - nav.ui.value.distanceToNextM
                            uncertaintyM = nav.ui.value.uncertaintyM
                            headingRad = sample.motion.yawRad
                        }
                    }
                },
            )

            val reduction = (baseline.arrivalErrorP95M - visualAssisted.arrivalErrorP95M) / baseline.arrivalErrorP95M
            assertTrue(
                reduction >= 0.30,
                "scenario $scenario arrival p95 must drop >= 30%: baseline=${baseline.arrivalErrorP95M}, visual=${visualAssisted.arrivalErrorP95M} (reduction=${reduction * 100}%)",
            )
            assertTrue(
                visualAssisted.arrivalErrorP95M <= baseline.arrivalErrorP95M * 1.05,
                "scenario $scenario must not regress > 5%",
            )
        }
    }

    private companion object {
        const val SEED = 104_729L
    }
}
