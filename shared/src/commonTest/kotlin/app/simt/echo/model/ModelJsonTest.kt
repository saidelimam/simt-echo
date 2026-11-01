package app.simt.echo.model

import app.simt.echo.Fixtures
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Contract-freeze gate: the §5.2 model must survive EchoJson unchanged. */
class ModelJsonTest {

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
}
