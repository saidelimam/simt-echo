package app.simt.echo.ui.screens

import android.os.Looper
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.simt.echo.AppGraph
import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.model.EchoRoute as SavedRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.platform.Platform
import app.simt.echo.transfer.TransferService
import app.simt.echo.ui.EchoNavHost
import app.simt.echo.ui.EchoRoute
import app.simt.echo.ui.navigateOffRecord
import app.simt.echo.ui.reviewAfterRecord
import app.simt.echo.ui.screens.receive.ReceiveViewModel
import app.simt.echo.ui.screens.share.ShareViewModel
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.robolectric.Shadows
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W7-09 headless user journeys: the legs the W7-03/W7-04/W7-05 suites
 * left open, driven through the real [EchoNavHost] (or the real
 * Share/Receive view models) over fixture data.
 *
 * - (a) empty Home → Record entry.
 * - (b) Home → RouteDetail → Align → Navigate → Arrived on a replayed
 *   short hop. W7-04's [HostSimulateJourneyTest] proves the flag flow on
 *   the 45 m fixture (a 45 s replay); this suite replays a 4 m hop so
 *   the ARRIVED leg itself renders headlessly in seconds.
 * - (c) draft → Review → Save → RouteDetail from a real save.
 * - (d) Share offer → Receive preview model with an in-memory transport
 *   at the seams (no sockets): the QR Share offers is the QR Receive
 *   previews, and nothing is saved before the user confirms.
 *
 * Behaviour only: destinations entered, screens rendered, preview
 * fields carried — never back-stack internals beyond the entered route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class HostCoreLoopJourneyTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    /**
     * Waits for [value] while pumping the Robolectric main looper.
     *
     * This test drives the real ViewModels without ever composing a screen,
     * so `compose.waitUntil` has no controlled clock to advance and the
     * `viewModelScope` work never runs. Idling the looper is what actually
     * moves it (Robolectric's default looper mode is PAUSED).
     */
    private fun <T : Any> awaitValue(timeoutMs: Long = 10_000, value: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            value()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for a value")
    }

    private fun testGraph(): AppGraph {
        val dir = Files.createTempDirectory("w7-09-journey").toString()
        val platform = object : Platform {
            override val name: String = "host-test"
            override val dataDir: String = dir
        }
        return AppGraph(platform = platform)
    }

    private inline fun <reified T : EchoRoute> current(nav: NavHostController): T =
        nav.currentBackStackEntry?.toRoute<T>()
            ?: error("expected route on top")

    /**
     * Straight corridor whose measured path runs 4 m past the door: the
     * replay overshoots the destination, so the engine auto-arrives
     * (remaining ≤ −3 m) with no confirmation tap — the full loop renders
     * headlessly in ~8 s instead of 45 s.
     */
    private fun shortHopRoute(): SavedRoute = SavedRoute(
        routeId = "fixture-short-hop",
        name = "Short hop (fixture)",
        createdAtEpochMs = 1_762_000_000_000L,
        creatorLabel = "fixture",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 4.0,
        start = StartAnchor(
            description = "Lobby",
            photoAssetId = null,
            alignmentHint = "Face the doors",
        ),
        nodes = listOf(
            RouteNode(id = "hop-n0", type = NodeType.START, atDistanceM = 0.0, label = "Lobby"),
            RouteNode(id = "hop-n1", type = NodeType.DESTINATION, atDistanceM = 4.0, label = "Front door"),
        ),
        legs = listOf(
            RouteLeg(fromNode = "hop-n0", toNode = "hop-n1", lengthM = 4.0, headingRelDeg = 0.0),
        ),
        path = (0..16).map { i -> PathPoint(d = i * 0.5, x = 0.0, y = i * 0.5) },
        landmarks = emptyList(),
    )

    private class FakeTransport : PeerTransport {
        var payload: ByteArray? = null
        var token: String? = null

        override suspend fun offer(payload: ByteArray, token: String): Endpoint {
            this.payload = payload
            this.token = token
            return Endpoint("192.168.1.20", 51234)
        }

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray {
            check(token == this.token) { "token mismatch" }
            return payload ?: error("nothing served")
        }

        override fun stopOffer() = Unit
    }

    private class FakeHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    private class FakeSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }

    @Test
    fun emptyHome_recordEntryOpensRecord() {
        val graph = testGraph()
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        compose.onNodeWithText("Walk it once. Share the way.").assertExists()
        compose.onNodeWithText("Record").performClick()

        compose.runOnIdle {
            assertEquals(EchoRoute.Record(), current<EchoRoute.Record>(nav))
        }
        // Record entered with its real screen: capture controls, or the
        // honest camera rationale when the gate denies — never Home again.
        compose.runOnIdle {
            val entered = listOf("Stand at the start", "Start", "Camera needed").any { marker ->
                compose.onAllNodesWithText(marker, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(entered, "Record entry shows the Record screen")
        }
    }

    @Test
    fun finishedRecordSession_replacedByReview_neverLeftBelowIt() {
        val graph = testGraph()
        // The draft a just-finished recording saves, so Review has a real
        // walk to reopen after the handoff.
        graph.draftRepository.saveDraftRoute(
            Fixtures.L_ROUTE.copy(routeId = "draft-finished", name = "Finished walk"),
        )
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        compose.onNodeWithText("Record").performClick()
        compose.runOnIdle {
            assertEquals(EchoRoute.Record(), current<EchoRoute.Record>(nav))
        }

        // The finish sheet's save runs exactly this handoff (EchoNavHost's
        // onSaved). Review replaces Record: a completed session leaves no
        // recording screen behind for Back to land on.
        compose.runOnIdle { nav.navigateOffRecord(reviewAfterRecord("draft-finished")) }
        compose.runOnIdle {
            assertEquals(EchoRoute.Review("draft-finished"), current<EchoRoute.Review>(nav))
        }
        // Back leaves Review for Home and stops there: the finished Record
        // entry was replaced, not stacked underneath it. A second pop has
        // nothing to reach — with Record still stacked it would succeed.
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle {
            assertFalse(nav.popBackStack(), "Back from Review reaches Home and stops there")
        }
    }

    @Test
    fun shortHop_replaysHomeToArrived() {
        val graph = testGraph()
        graph.routeRepository.save(shortHopRoute())
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        compose.onNodeWithTag("CosmosRecord:Short hop (fixture)").performClick()
        compose.onNodeWithText("Simulate walk").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(
                EchoRoute.Align("fixture-short-hop", simulate = true),
                current<EchoRoute.Align>(nav),
            )
        }
        compose.onNodeWithText("I'm aligned").performClick()
        compose.runOnIdle {
            assertEquals(
                EchoRoute.Navigate("fixture-short-hop", simulate = true),
                current<EchoRoute.Navigate>(nav),
            )
        }

        // The replay walks the saved geometry past the door on the test
        // clock (its 50 ms ticks are Main delays, which wall-clock
        // waiting never advances headlessly); the engine auto-arrives on
        // overshoot, landing on Arrived through the host's onArrived
        // wiring (W7-04), not a stub.
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Front door").assertExists()
        compose.onNodeWithText("Done").assertExists()
        compose.runOnIdle {
            assertEquals(EchoRoute.Arrived("fixture-short-hop"), current<EchoRoute.Arrived>(nav))
        }
    }

    @Test
    fun draftReview_saveLandsOnRouteDetail() {
        val graph = testGraph()
        graph.draftRepository.saveDraftRoute(
            Fixtures.L_ROUTE.copy(routeId = "draft-journey-1", name = "Journey draft walk"),
        )
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        // W8-06: drafts are Cosmos records (title + Draft badge), not the
        // pre-Cosmos "name · Draft" buttons — same destination, new row.
        compose.onNodeWithTag("CosmosRecord:Untitled draft").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Journey draft walk").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(EchoRoute.Review("draft-journey-1"), current<EchoRoute.Review>(nav))
        }

        compose.onNodeWithText("Save ECHO").performScrollTo()
        compose.onNodeWithText("Save ECHO").performClick()
        compose.mainClock.advanceTimeBy(5_000)
        compose.runOnIdle {
            val detail = runCatching { current<EchoRoute.RouteDetail>(nav) }.getOrNull()
            val top = nav.currentBackStackEntry?.destination?.route
            assertNotNull(detail, "expected RouteDetail on top after save, has $top")
            assertEquals("draft-journey-1", detail.routeId)
        }
        // The saved route opens as a real RouteDetail: the W7-04 loop
        // entries are offered for the route the user just saved.
        compose.onNodeWithText("Navigate").assertExists()
        compose.onNodeWithText("Share").assertExists()
        compose.runOnIdle {
            assertEquals("draft-journey-1", current<EchoRoute.RouteDetail>(nav).routeId)
        }
    }

    @Test
    fun draftReview_saveDraft_returnsHomeAndKeepsTheDraft() {
        val graph = testGraph()
        graph.draftRepository.saveDraftRoute(
            Fixtures.L_ROUTE.copy(routeId = "draft-kept-1", name = "Kept draft walk"),
        )
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            EchoNavHost(graph = graph, navController = nav)
        }

        compose.onNodeWithTag("CosmosRecord:Untitled draft").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Kept draft walk").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle {
            assertEquals(EchoRoute.Review("draft-kept-1"), current<EchoRoute.Review>(nav))
        }

        // Save draft keeps the walk on this phone: no ECHO lands in routes/,
        // the draft survives with its edits, and Home is the screen the user
        // gets back to.
        compose.onNodeWithText("Save draft").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Drafts").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Save ECHO").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("draft-kept-1"), graph.draftRepository.listDraftIds())
            assertEquals(
                "Kept draft walk",
                graph.draftRepository.loadDraftRoute("draft-kept-1")?.name,
            )
            assertTrue(graph.routeRepository.list().isEmpty(), "Save draft must not save an ECHO")
        }
    }

    @Test
    fun shareOffer_receivePreviewMatches() {
        val dir = Files.createTempDirectory("w7-09-share").toString().toPath()
        val store = FileStore(FileSystem.SYSTEM, dir)
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val service = TransferService(
            transport = FakeTransport(),
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )

        // Share offers through its real view model; the QR must be one a
        // scanner accepts (no raw ids, no exception text — W7-06 copy).
        val shareVm = ShareViewModel(Fixtures.L_ROUTE.routeId, service, FakeHaptics(), FakeSharing())
        val qrUri = assertNotNull(awaitValue { shareVm.ui.value.qrUri }, "Share offers a QR")
        assertTrue(qrUri.startsWith("simt-echo://"), "offer QR uses the scan scheme")

        // The sealed bytes behind the QR open to the offered route.
        val body = runBlocking { service.fetchPreview(qrUri) }
        assertEquals(Fixtures.L_ROUTE.routeId, body.route.routeId)
        assertEquals(Fixtures.L_ROUTE.name, body.route.name)

        // Receive previews exactly that offer — fields, not internals —
        // and saves nothing before the user confirms.
        val receiveVm = ReceiveViewModel(service, FakeSharing())
        receiveVm.onScanned(qrUri)
        val preview = assertNotNull(awaitValue { receiveVm.ui.value.preview }, "Receive previews the offer")
        assertEquals("L route (fixture)", preview.name)
        assertEquals(45.0, preview.lengthM)
        assertEquals(2, preview.turns)
        assertEquals(1, preview.landmarkCount)
        assertEquals("fixture", preview.creatorLabel)
        assertNull(preview.expiresAtEpochMs)
        assertNull(receiveVm.ui.value.savedRouteId, "preview must not save before Save")
    }
}
