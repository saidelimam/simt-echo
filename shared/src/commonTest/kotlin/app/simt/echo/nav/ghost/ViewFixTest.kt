package app.simt.echo.nav.ghost

import app.simt.echo.capture.visual.ParallaxProfiler
import app.simt.echo.capture.visual.withParallax
import app.simt.echo.capture.wrapPi
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.NavigatorEngine
import app.simt.echo.model.ParallaxProfile
import app.simt.echo.model.ViewStrip
import app.simt.echo.nav.NavInput
import app.simt.echo.nav.Navigator
import app.simt.echo.platform.MotionUpdate
import app.simt.echo.sim.SimEnvironment
import app.simt.echo.sim.SimPhone
import app.simt.echo.sim.SimPose
import app.simt.echo.sim.SimWalk
import app.simt.echo.sim.SimWorld
import kotlin.io.encoding.Base64
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * W17-03 gates: ViewFix strip sequence matching recovers pure heading and
 * distance offsets (±3° and ±0.5 m on SimWorld), matches across lighting
 * changes (gamma/gain) and 20 % moving occluders, rejects repeated-corridor
 * decoys via the runner-up margin, tolerates two-phone FOV mismatch, and bounds
 * heading drift to ≤ 3° at each fix.
 */
class ViewFixTest {

    private fun renderStrip(
        world: SimWorld,
        pose: SimPose,
        tNs: Long = 1_000_000_000L,
    ): ByteArray {
        val frame = world.render(pose, tNs)
        return ParallaxProfiler.extractViewStripFrom240x180(frame.luma)
    }

    private fun buildProfileAtWaypoint(
        world: SimWorld,
        waypointY: Double = 55.0,
        routeDistM: Double = 20.0,
        rhoPerM: Double = 0.20,
    ): Pair<ParallaxProfile, ByteArray> {
        val refStrip = renderStrip(world, SimPose(x = 0.0, y = waypointY, z = 1.5, yawRad = 0.0))
        val buckets = (30.0 / ParallaxProfile.STEP_M).toInt()
        val code = ParallaxProfiler.quantiseRho(rhoPerM)
        val depthBytes = ByteArray(buckets) { code }
        val trackBytes = ByteArray(buckets) { 220.toByte() }
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(depthBytes),
            trackability = Base64.encode(trackBytes),
            viewStrips = listOf(ViewStrip(atDistanceM = routeDistM, dataB64 = Base64.encode(refStrip))),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        return profile to refStrip
    }

    @Test
    fun pureHeadingAndDistanceOffsetsRecoveredOnSimWorld() {
        val phone = SimPhone(horizontalFovDeg = 64.0)
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed = SEED, pixelSamplesPerAxis = 4)
        // At y = 55.0 m facing the end wall at y = 60.0 m, Z = 5.0 m so ρ = 1/Z = 0.20 /m.
        val (profile, _) = buildProfileAtWaypoint(world, waypointY = 55.0, routeDistM = 20.0, rhoPerM = 0.20)
        val matcher = ViewFixMatcher(profile)
        val fovRad = phone.horizontalFovDeg * PI / 180.0

        // 1. Heading offsets of -3° and +3°.
        for (deg in listOf(-3.0, 3.0)) {
            matcher.reset()
            val trueDHeading = deg * PI / 180.0
            val live = renderStrip(world, SimPose(x = 0.0, y = 55.0, z = 1.5, yawRad = trueDHeading))
            val fix = assertNotNull(matcher.matchObservation(live, odoM = 20.0, meanS = 20.0, sigmaM = 1.5, fovRad = fovRad))
            val errDeg = abs(fix.dHeadingRad - trueDHeading) * 180.0 / PI
            assertTrue(
                errDeg <= 1.5,
                "heading offset $deg° should be recovered within 1.5°, got ${fix.dHeadingRad * 180.0 / PI}° (err=$errDeg°)",
            )
        }

        // 2. Along-route distance offsets of -0.5 m and +0.5 m.
        for (trueDs in listOf(-0.5, 0.5)) {
            matcher.reset()
            val live = renderStrip(world, SimPose(x = 0.0, y = 55.0 + trueDs, z = 1.5, yawRad = 0.0))
            val fix = assertNotNull(matcher.matchObservation(live, odoM = 20.0 + trueDs, meanS = 20.0, sigmaM = 1.5, fovRad = fovRad))
            assertTrue(
                fix.dSM * trueDs >= -0.15 && abs(fix.dSM - trueDs) <= 0.45,
                "distance offset $trueDs m should be recovered within 0.45 m, got ${fix.dSM} m",
            )
        }
    }

    @Test
    fun lightingChangeGammaGainAndTwentyPercentMovingOccludersStillMatch() {
        val creatorPhone = SimPhone(horizontalFovDeg = 64.0, exposure = 1.0, gamma = 1.0)
        val creatorWorld = SimWorld(SimEnvironment.CORRIDOR, creatorPhone, seed = SEED, pixelSamplesPerAxis = 4)
        val (profile, _) = buildProfileAtWaypoint(creatorWorld, waypointY = 55.0, routeDistM = 20.0, rhoPerM = 0.20)
        val matcher = ViewFixMatcher(profile)

        // Follower phone with strong lighting change (exposure 0.68, gamma 1.25).
        val litPhone = SimPhone(horizontalFovDeg = 64.0, exposure = 0.68, gamma = 1.25)
        val litWorld = SimWorld(SimEnvironment.CORRIDOR, litPhone, seed = SEED, pixelSamplesPerAxis = 4)
        val litFrame = litWorld.render(SimPose(0.0, 55.0, 1.5, yawRad = 2.0 * PI / 180.0), 1_000_000_000L)

        // Corrupt 20% of the central band columns with a uniform moving billboard occluder.
        val occludedLuma = litFrame.luma.copyOf()
        val occStartX = 40
        val occEndX = 40 + (240 * 0.20).toInt() // 20% width
        for (y in 40 until 140) {
            for (x in occStartX until occEndX) {
                occludedLuma[y * 240 + x] = 96.toByte()
            }
        }
        val occludedStrip = ParallaxProfiler.extractViewStripFrom240x180(occludedLuma)
        val fix = assertNotNull(
            matcher.matchObservation(
                liveStrip = occludedStrip,
                odoM = 20.0,
                meanS = 20.0,
                sigmaM = 1.5,
                fovRad = litPhone.horizontalFovDeg * PI / 180.0,
            ),
            "lighting change + 20% occluder must still match",
        )
        assertTrue(fix.confidence01 >= ViewFixMatcher.MIN_CONFIDENCE)
        val headingErrDeg = abs(fix.dHeadingRad - 2.0 * PI / 180.0) * 180.0 / PI
        assertTrue(headingErrDeg <= 2.0, "heading offset under lighting + 20% occluder must stay within 2°, got $headingErrDeg°")
    }

    @Test
    fun repeatedCorridorDecoyIsRejectedByRunnerUpMargin() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = SEED, pixelSamplesPerAxis = 2)
        val strip = renderStrip(world, SimPose(0.0, 55.0, 1.5, 0.0))
        val encoded = Base64.encode(strip)

        // Two identical waypoints 4 m apart within the search window (repeated corridor decoy).
        val decoyProfile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(60) { 10 }),
            trackability = Base64.encode(ByteArray(60) { 220.toByte() }),
            viewStrips = listOf(
                ViewStrip(atDistanceM = 18.0, dataB64 = encoded),
                ViewStrip(atDistanceM = 22.0, dataB64 = encoded),
            ),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        val matcher = ViewFixMatcher(decoyProfile)
        assertFalse(
            matcher.match(strip, odoM = 20.0, meanS = 20.0, sigmaM = 2.0),
            "repeated-corridor decoy with zero runner-up margin must be rejected",
        )
    }

    @Test
    fun twoPhoneFovMismatchStaysWithinTolerance() {
        val creatorPhone = SimPhone(horizontalFovDeg = 60.0)
        val followerPhone = SimPhone(horizontalFovDeg = 69.0)
        val creatorWorld = SimWorld(SimEnvironment.CORRIDOR, creatorPhone, seed = SEED, pixelSamplesPerAxis = 4)
        val followerWorld = SimWorld(SimEnvironment.CORRIDOR, followerPhone, seed = SEED, pixelSamplesPerAxis = 4)

        val (profile, _) = buildProfileAtWaypoint(creatorWorld, waypointY = 55.0, routeDistM = 20.0, rhoPerM = 0.20)
        val matcher = ViewFixMatcher(profile)
        val live = renderStrip(followerWorld, SimPose(0.0, 55.0, 1.5, yawRad = -2.0 * PI / 180.0))

        val fix = assertNotNull(
            matcher.matchObservation(
                liveStrip = live,
                odoM = 20.0,
                meanS = 20.0,
                sigmaM = 1.5,
                fovRad = followerPhone.horizontalFovDeg * PI / 180.0,
            ),
            "60° vs 69° two-phone FOV mismatch must still match via stretch grid",
        )
        val errDeg = abs(fix.dHeadingRad - (-2.0 * PI / 180.0)) * 180.0 / PI
        assertTrue(errDeg <= 2.5, "two-phone FOV mismatch heading error must be <= 2.5°, got $errDeg°")
    }

    @Test
    fun headingDriftScenarioWithViewFixStaysBoundedWithinThreeDegreesAtEachFix() {
        val phone = SimPhone(horizontalFovDeg = 64.0)
        val world = SimWorld(SimEnvironment.CORRIDOR, phone, seed = SEED, pixelSamplesPerAxis = 2)
        val fovRad = phone.horizontalFovDeg * PI / 180.0

        // Build stored view strips every 4 m along a 24 m straight route (y = 34..58 m).
        val waypoints = listOf(4.0, 8.0, 12.0, 16.0, 20.0, 24.0)
        val strips = waypoints.map { d ->
            val pose = SimPose(0.0, 34.0 + d, 1.5, yawRad = 0.0)
            ViewStrip(atDistanceM = d, dataB64 = Base64.encode(renderStrip(world, pose)))
        }
        val buckets = (24.0 / ParallaxProfile.STEP_M).toInt()
        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(buckets) { ParallaxProfiler.quantiseRho(0.20) }),
            trackability = Base64.encode(ByteArray(buckets) { 220.toByte() }),
            viewStrips = strips,
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )
        val route = SimWalk.straightRoute(24.0).withParallax(profile)
        val nav = Navigator(
            route = route,
            config = EchoConfig(navigatorEngine = NavigatorEngine.GHOST, visualAssist = true),
            ghostSeed = SEED,
        )

        nav.onInput(NavInput.Motion(MotionUpdate(1_000_000_000L, 0.0, 0.0, 0, false, null, "test")))
        nav.onInput(NavInput.Aligned)

        // Sensor yaw drifts by +1.0° every 4 m (up to +6° by 24 m), while the walker physically walks straight (camera yaw = 0°).
        var fixesApplied = 0
        for (step in 1..48) {
            val d = step * 0.5
            val tNs = 1_000_000_000L + step * 500_000_000L
            val driftedSensorYaw = (d / 4.0) * (1.0 * PI / 180.0)
            nav.onInput(
                NavInput.Motion(
                    MotionUpdate(tNs, d, driftedSensorYaw, step, true, null, "test", headingQuality = 0.90),
                ),
            )
            if (d in waypoints) {
                // Live camera sees the corridor rotated by the current uncompensated sensor drift error.
                val currentArrowError = -nav.ui.value.arrowRad
                val liveStrip = renderStrip(world, SimPose(0.0, 34.0 + d, 1.5, yawRad = currentArrowError))
                // Apply two consecutive ViewFix passes at the waypoint to settle the soft update.
                val fix1 = nav.onViewStrip(liveStrip, fovRad)
                if (fix1 != null) {
                    fixesApplied++
                    val residualAfterfirst = -nav.ui.value.arrowRad
                    val liveStrip2 = renderStrip(world, SimPose(0.0, 34.0 + d, 1.5, yawRad = residualAfterfirst))
                    nav.onViewStrip(liveStrip2, fovRad)
                }
                val arrowDegAfterFix = abs(wrapPi(nav.ui.value.arrowRad)) * 180.0 / PI
                assertTrue(
                    arrowDegAfterFix <= 3.0,
                    "at waypoint $d m, heading error after ViewFix must be <= 3°, got $arrowDegAfterFix°",
                )
            }
        }
        assertTrue(fixesApplied >= 4, "expected at least 4 ViewFix matches along the walk, got $fixesApplied")
    }

    @Test
    fun overlayRecordedUnderChangedLightingImprovesDecoyLitMatchAndIgnoresCorruptedOverlayEntries() {
        val world = SimWorld(SimEnvironment.CORRIDOR, seed = SEED, pixelSamplesPerAxis = 2)
        val creatorStripAt20 = renderStrip(world, SimPose(0.0, 55.0, 1.5, 0.0))

        //Under changed lighting/appearance (e.g. evening shadow shifting column contrast on half the scene),
        // the live strip at 20 m only partially correlates with the original creator strip, tying with a decoy at 16 m.
        val changedLightStripAt20 = ByteArray(ViewStrip.BYTES) { i ->
            if (i < 32) (-creatorStripAt20[i]).toByte() else creatorStripAt20[i]
        }
        val decoyStripAt16 = changedLightStripAt20.copyOf().also { arr ->
            for (i in 0 until 28) arr[i] = creatorStripAt20[i]
        }

        val profile = ParallaxProfile(
            inverseDepth = Base64.encode(ByteArray(60) { ParallaxProfiler.quantiseRho(0.20) }),
            trackability = Base64.encode(ByteArray(60) { 220.toByte() }),
            viewStrips = listOf(
                ViewStrip(atDistanceM = 16.0, dataB64 = Base64.encode(decoyStripAt16)),
                ViewStrip(atDistanceM = 20.0, dataB64 = Base64.encode(creatorStripAt20)),
            ),
            estimatorId = ParallaxProfiler.DEFAULT_ESTIMATOR_ID,
            intrinsicsClass = ParallaxProfiler.DEFAULT_INTRINSICS_CLASS,
        )

        val withoutOverlay = ViewFixMatcher(profile)
        val matchedWithoutOverlay = withoutOverlay.match(changedLightStripAt20, odoM = 20.0, meanS = 18.0, sigmaM = 2.0)

        // Now provide the follower's RouteMemory overlay at 20.0 m (recorded in this lighting) plus corrupted entries.
        val corruptedEntries = listOf(
            ViewStrip(atDistanceM = Double.NaN, dataB64 = Base64.encode(changedLightStripAt20)),
            ViewStrip(atDistanceM = -4.0, dataB64 = Base64.encode(changedLightStripAt20)),
            ViewStrip(atDistanceM = 20.0, dataB64 = "!!!not-base64!!!"),
            ViewStrip(atDistanceM = 20.0, dataB64 = Base64.encode(ByteArray(12))),
        )
        val validOverlay = ViewStrip(atDistanceM = 20.0, dataB64 = Base64.encode(changedLightStripAt20))
        val withOverlay = ViewFixMatcher(profile, extraStrips = corruptedEntries + validOverlay)

        assertTrue(
            withOverlay.match(changedLightStripAt20, odoM = 20.0, meanS = 18.0, sigmaM = 2.0),
            "overlay recorded under changed lighting must enable a clean match at 20.0 m",
        )
        kotlin.test.assertEquals(20.0, withOverlay.lastNodeS, 1e-6)
        assertTrue(
            !matchedWithoutOverlay || withOverlay.lastConfidence01 > withoutOverlay.lastConfidence01,
            "overlay must improve match confidence over the un-overlaid matcher",
        )
    }

    private companion object {
        const val SEED = 1_234_567L
    }
}
