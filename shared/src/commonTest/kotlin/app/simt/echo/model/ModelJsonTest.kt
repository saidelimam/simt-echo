package app.simt.echo.model

import app.simt.echo.Fixtures
import app.simt.echo.V1RouteView
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Contract-freeze gate: the §5.2 model must survive EchoJson unchanged. */
class ModelJsonTest {

    @Test
    fun trialGateCountersRoundTripAndDefault() {
        val trial = Trial(
            trialId = "t1",
            routeId = "r1",
            mode = TrialMode.ECHO,
            device = "pixel",
            startedAtEpochMs = 1_000L,
            durationS = 10.0,
            distanceM = 12.0,
            arrived = true,
            visualGateAccepted = 7,
            visualGateRejected = 3,
            visualGateUnwitnessed = 2,
            visualGateSuspects = 1,
        )
        assertEquals(trial, EchoJson.decodeFromString<Trial>(EchoJson.encodeToString(trial)))
        val legacy = """{"trialId":"old","routeId":"r","mode":"ECHO","device":"d","startedAtEpochMs":1,"durationS":1.0,"distanceM":1.0,"arrived":true}"""
        val decoded = EchoJson.decodeFromString<Trial>(legacy)
        assertEquals(0, decoded.visualGateAccepted)
        assertEquals(0, decoded.visualGateRejected)
        assertEquals(0, decoded.visualGateUnwitnessed)
        assertEquals(0, decoded.visualGateSuspects)
    }

    @Test
    fun echoRouteRoundTrips() {
        val decoded = EchoJson.decodeFromString<EchoRoute>(
            EchoJson.encodeToString(Fixtures.L_ROUTE),
        )
        assertEquals(Fixtures.L_ROUTE, decoded)
    }

    @Test
    fun captureLogRoundTrips() {
        val log = CaptureLog(
            draftId = "d1",
            startedAtEpochMs = 1_762_000_000_000L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = 5_000L,
            samples = listOf(
                CaptureSample(tNs = 6_000L, d = 1.4, yaw = 0.1, p = 1013.2),
                CaptureSample(tNs = 7_000L, d = 2.1, yaw = 0.2),
            ),
            marks = listOf(Mark(tNs = 7_000L, d = 2.1, label = "pillar")),
            startPhoto = "start.jpg",
            routeName = "Garage",
            destinationLabel = "Elevator",
        )
        val decoded = EchoJson.decodeFromString<CaptureLog>(EchoJson.encodeToString(log))
        assertEquals(log, decoded)
    }

    @Test
    fun bundleBodyRoundTrips() {
        val body = BundleBody(
            route = Fixtures.L_ROUTE,
            assets = listOf(
                Asset(id = "a_lm", mime = "image/jpeg", b64 = "BBBB"),
                Asset(id = "a_start", mime = "image/jpeg", b64 = "AAAA"),
            ),
        )
        val decoded = EchoJson.decodeFromString<BundleBody>(EchoJson.encodeToString(body))
        assertEquals(body, decoded)
        assertEquals(body.assets.map { it.id }, body.assets.map { it.id }.sorted())
    }

    @Test
    fun unknownKeysAreTolerated() {
        val json = """{"draftId":"d","startedAtEpochMs":1,"motionSource":"m","videoStartNs":2,"samples":[],"marks":[],"startPhoto":"s.jpg","futureField":42}"""
        val decoded = EchoJson.decodeFromString<CaptureLog>(json)
        assertEquals("d", decoded.draftId)
    }

    @Test
    fun nullsAreOmittedOnEncode() {
        val route = Fixtures.L_ROUTE
        val text = EchoJson.encodeToString(route)
        assertFalse(text.contains("\"ai\":null"), text)
        // Nulls inside present objects are omitted too (explicitNulls = false).
        assertFalse(text.contains("null"), text)
    }

    @Test
    fun defaultsAreEncoded() {
        val text = EchoJson.encodeToString(Fixtures.L_ROUTE)
        assertTrue(text.contains("\"coordinateSystem\":\"relative_to_confirmed_start\""), text)
        assertTrue(text.contains("\"schemaVersion\":1"), text)
    }

    @Test
    fun idConventionsHold() {
        assertEquals("n0", EchoIds.nodeId(0))
        assertEquals("n12", EchoIds.nodeId(12))
        assertEquals("lm_n1_0", EchoIds.landmarkId("n1", 0))
        assertEquals("a_start", EchoIds.assetId("start"))
    }

    @Test
    fun fixtureRouteIsInternallyConsistent() {
        val route = Fixtures.L_ROUTE
        assertEquals(route.totalLengthM, route.legs.sumOf { it.lengthM })
        assertEquals(route.nodes.last().atDistanceM, route.totalLengthM)
        assertEquals(0.0, route.path.first().d)
        assertEquals(route.totalLengthM, route.path.last().d)
        assertEquals(NodeType.START, route.nodes.first().type)
        assertEquals(NodeType.DESTINATION, route.nodes.last().type)
        assertEquals(
            listOf(TurnDir.RIGHT, TurnDir.LEFT),
            route.nodes.filter { it.type == NodeType.TURN }.map { it.turn },
        )
    }

    // ---- W10-05 bundle v2 contract freeze ----

    @Test
    fun v1JsonWithoutPerceptionReadsWithNullPerception() {
        // Frozen v1 shape: no "perception" key at all (explicitNulls=false).
        val v1 = """{"schemaVersion":1,"routeId":"v1","name":"V1","createdAtEpochMs":1,"creatorLabel":"c","motionSource":"m","totalLengthM":10.0,"start":{"description":"s","alignmentHint":"h"},"nodes":[],"legs":[],"path":[]}"""
        val decoded = EchoJson.decodeFromString<EchoRoute>(v1)
        assertEquals(1, decoded.schemaVersion)
        assertNull(decoded.perception)
        assertFalse(EchoSchemaVersion.needsUpdate(decoded))
    }

    @Test
    fun v2RouteRoundTrips() {
        val route = Fixtures.V2_ROUTE
        assertEquals(EchoSchemaVersion.V2, route.schemaVersion)
        val decoded = EchoJson.decodeFromString<EchoRoute>(EchoJson.encodeToString(route))
        assertEquals(route, decoded)
        assertNotNull(decoded.perception)
        assertEquals(2, decoded.perception!!.fingerprints.size)
        assertEquals(10, decoded.perception!!.waypointPrints.size)
        assertEquals(90, decoded.perception!!.magProfile!!.magnitude.size)
    }

    @Test
    fun v1ReaderIgnoresV2Perception() {
        // The decided v2→v1 behaviour: a v1 reader drops `perception` and
        // still gets the geometry — ignore, never a crash.
        val v2json = EchoJson.encodeToString(Fixtures.V2_ROUTE)
        assertTrue(v2json.contains("\"perception\""), v2json)
        val viewed = EchoJson.decodeFromString<V1RouteView>(v2json)
        assertEquals(Fixtures.V2_ROUTE.routeId, viewed.routeId)
        assertEquals(Fixtures.V2_ROUTE.name, viewed.name)
        assertEquals(Fixtures.V2_ROUTE.totalLengthM, viewed.totalLengthM)
        assertEquals(Fixtures.V2_ROUTE.nodes, viewed.nodes)
    }

    @Test
    fun futureSchemaVersionNeedsUpdate() {
        assertFalse(EchoSchemaVersion.needsUpdate(Fixtures.L_ROUTE))
        assertFalse(EchoSchemaVersion.needsUpdate(Fixtures.V2_ROUTE))
        assertTrue(EchoSchemaVersion.needsUpdate(Fixtures.V2_ROUTE.copy(schemaVersion = 99)))
    }

    @Test
    fun perceptionValidationRejectsBadShapes() {
        val good = Fixtures.V2_ROUTE.perception!!
        // Four prints on one node (cap is three).
        val four = NodeFingerprint(
            nodeId = "n1",
            prints = List(4) {
                EmbeddedPrint("echo-embed-01", 1, Fixtures.printB64(it))
            },
        )
        assertFailsWith<IllegalArgumentException> {
            good.copy(fingerprints = listOf(four)).validate()
        }
        // Truncated vector (not int8[256]).
        val short = EmbeddedPrint("echo-embed-01", 1, Fixtures.printB64(0).dropLast(4))
        assertFailsWith<IllegalArgumentException> {
            good.copy(fingerprints = listOf(NodeFingerprint("n1", listOf(short)))).validate()
        }
        // Magnitude/vertical arrays must pair up.
        assertFailsWith<IllegalArgumentException> {
            good.copy(magProfile = MagProfile(magnitude = listOf(1), vertical = emptyList())).validate()
        }
        // Health score is 0..1.
        assertFailsWith<IllegalArgumentException> {
            good.copy(health = RouteHealth(score01 = 1.5, checkedAtEpochMs = 1L)).validate()
        }
    }

    @Test
    fun oversizePerceptionFailsValidation() {
        // 250 waypoint prints ≈ 100 KB of base64: over the 64 KB cap.
        val big = Fixtures.V2_ROUTE.perception!!.copy(
            waypointPrints = List(250) { i ->
                WaypointPrint(
                    atDistanceM = i.toDouble(),
                    print = EmbeddedPrint("echo-embed-01", 1, Fixtures.printB64(i)),
                )
            },
        )
        val e = assertFailsWith<IllegalArgumentException> { big.validate() }
        assertTrue(e.message!!.contains("65536"), e.message)
    }

    @Test
    fun v20FixtureReadsWithoutParallaxInV21() {
        val route = EchoJson.decodeFromString<EchoRoute>(EchoJson.encodeToString(Fixtures.V2_ROUTE))
        assertEquals(2, route.schemaVersion)
        assertNull(requireNotNull(route.perception).parallax)
        assertEquals(Fixtures.V2_ROUTE.perception, route.perception)
    }

    @Test
    fun v21RoundTripsWithoutChangingSchemaVersion() {
        val route = ParallaxFixtures.route
        val decoded = EchoJson.decodeFromString<EchoRoute>(EchoJson.encodeToString(route))
        assertEquals(route, decoded)
        assertEquals(2, decoded.schemaVersion)
        assertEquals(ParallaxFixtures.profile, requireNotNull(decoded.perception).parallax)
    }

    @Test
    fun v20ReaderIgnoresParallaxAndRetainsExistingPerception() {
        val perception = requireNotNull(ParallaxFixtures.route.perception)
        val viewed = EchoJson.decodeFromString<V20PerceptionView>(EchoJson.encodeToString(perception))
        assertEquals(perception.fingerprints, viewed.fingerprints)
        assertEquals(perception.waypointPrints, viewed.waypointPrints)
        assertEquals(perception.magProfile, viewed.magProfile)
        assertEquals(perception.sigilSeed, viewed.sigilSeed)
        assertEquals(perception.health, viewed.health)
    }

    @Test
    fun unknownParallaxVersionDecodesButIsNotUsed() {
        val profile = ParallaxFixtures.profile.copy(parallaxVersion = 99)
        val decoded = EchoJson.decodeFromString<ParallaxProfile>(EchoJson.encodeToString(profile))
        assertFalse(decoded.compatibleWith(profile.estimatorId))
    }
}
