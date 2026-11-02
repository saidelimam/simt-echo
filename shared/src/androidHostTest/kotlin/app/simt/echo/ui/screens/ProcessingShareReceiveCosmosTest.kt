package app.simt.echo.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.simt.echo.data.DraftRepository
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.host.HostTestResourcesShadow
import app.simt.echo.host.RobolectricActivityRule
import app.simt.echo.media.ClipInfo
import app.simt.echo.media.CropBox
import app.simt.echo.media.MediaTools
import app.simt.echo.model.CaptureLog
import app.simt.echo.model.CaptureSample
import app.simt.echo.model.EchoRoute
import app.simt.echo.model.NodeType
import app.simt.echo.model.PathPoint
import app.simt.echo.model.RouteLeg
import app.simt.echo.model.RouteNode
import app.simt.echo.model.StartAnchor
import app.simt.echo.model.TurnDir
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.FileSharing
import app.simt.echo.platform.HapticPattern
import app.simt.echo.platform.Haptics
import app.simt.echo.platform.PeerTransport
import app.simt.echo.transfer.CloudApi
import app.simt.echo.transfer.JobPoll
import app.simt.echo.transfer.JobStage
import app.simt.echo.transfer.TransferService
import app.simt.echo.transfer.UploadFile
import app.simt.echo.ui.screens.processing.ProcessingPhase
import app.simt.echo.ui.screens.processing.ProcessingConsentSheetTag
import app.simt.echo.ui.screens.processing.ProcessingViewModel
import app.simt.echo.ui.screens.receive.ReceiveReticleTag
import app.simt.echo.ui.screens.receive.ReceiveScannerFrame
import app.simt.echo.ui.screens.receive.ReceiveScannerTag
import app.simt.echo.ui.screens.share.ShareExportSectionTag
import app.simt.echo.ui.screens.share.ShareExportToggleTag
import app.simt.echo.ui.screens.share.ShareQrImageTag
import app.simt.echo.ui.screens.share.ShareQrTag
import app.simt.echo.ui.screens.share.ShareScreen
import app.simt.echo.ui.theme.EchoQrMin
import app.simt.echo.ui.theme.EchoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W8-09 Cosmos instrument gate for Processing/Share/Receive:
 *
 * - consent renders as the canopy sheet with the frozen privacy copy
 *   before any upload UI or submit exists (the VM ordering test
 *   `sendBeforeConsentAndDoubleSendNeverSubmit` stays untouched);
 * - the Share QR is the first read, legible at the frozen ≥260 dp size,
 *   with the export preview collapsed until opened;
 * - the Receive scanner is full-bleed (owns its parent width, not a
 *   fixed dp box) with the reticle motif.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, shadows = [HostTestResourcesShadow::class])
class ProcessingShareReceiveCosmosTest {

    private val activityRule = RobolectricActivityRule()

    @get:Rule
    val compose: AndroidComposeTestRule<RobolectricActivityRule, *> =
        AndroidComposeTestRule(activityRule, EmptyCoroutineContext) { it.activity }

    // ── shared fakes ──────────────────────────────────────────────

    private class FakeHaptics : Haptics {
        override fun play(pattern: HapticPattern) = Unit
    }

    private class FakeSharing : FileSharing {
        override suspend fun shareFile(path: Path, name: String, mime: String): Boolean = true
        override suspend fun pickDocument(): ByteArray? = null
    }

    private class FakeTransport(val downloads: MutableSharedFlow<Unit> = MutableSharedFlow()) : PeerTransport {
        override suspend fun offer(payload: ByteArray, token: String): Endpoint =
            Endpoint("192.168.1.20", 51234)

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
            error("Share screen never fetches")

        override fun stopOffer() = Unit

        override val served: Flow<Unit> get() = downloads
    }

    private class FakeMedia : MediaTools {
        override suspend fun extractFrame(video: Path, atMs: Long, maxSide: Int, out: Path) {
            out.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(out) { write("frame$atMs".encodeToByteArray()) }
        }

        override suspend fun cropJpeg(input: Path, box: CropBox, maxSide: Int, out: Path): Nothing =
            error("not exercised here")

        override suspend fun cutClip(
            video: Path,
            startMs: Long,
            endMs: Long,
            maxFps: Int,
            out: Path,
        ): ClipInfo {
            out.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
            FileSystem.SYSTEM.write(out) { write("clip".encodeToByteArray()) }
            return ClipInfo(out, frameCount = 12, durationMs = endMs - startMs)
        }
    }

    private class FakeCloudApi : CloudApi {
        val submits = mutableListOf<Pair<String, List<UploadFile>>>()

        override suspend fun submit(manifestJson: String, files: List<UploadFile>): String {
            submits += manifestJson to files
            return "job-1"
        }

        override suspend fun get(jobId: String): JobPoll =
            JobPoll("running", JobStage.SCOUTING)

        override suspend fun delete(jobId: String) = Unit

        override suspend fun samEnabled(): Boolean = true
    }

    private fun savedRoute(): EchoRoute = EchoRoute(
        routeId = "w8-09-share-route",
        name = "Cosmos share walk",
        createdAtEpochMs = 1_762_000_000_000L,
        creatorLabel = "tester",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 45.0,
        start = StartAnchor("Entrance", null, "Face north"),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Entrance"),
            RouteNode(id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "pillar"),
            RouteNode(id = "n2", type = NodeType.DESTINATION, atDistanceM = 45.0, label = "Elevator"),
        ),
        legs = listOf(RouteLeg("n0", "n1", 20.0, 0.0), RouteLeg("n1", "n2", 25.0, 90.0)),
        path = listOf(PathPoint(0.0, 0.0, 0.0), PathPoint(45.0, 15.0, 20.0)),
    )

    private fun shareService(): TransferService {
        val dir = Files.createTempDirectory("w8-09-share").toString().toPath()
        val store = FileStore(FileSystem.SYSTEM, dir)
        val routes = RouteRepository(store)
        routes.save(savedRoute())
        return TransferService(
            transport = FakeTransport(),
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
    }

    private fun draftRoute(): EchoRoute = EchoRoute(
        routeId = "draft-1",
        name = "Test walk",
        createdAtEpochMs = 1_762_000_000_000L,
        creatorLabel = "creator",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 45.0,
        start = StartAnchor("Entrance", "start.jpg", "Face north"),
        nodes = listOf(
            RouteNode(id = "n0", type = NodeType.START, atDistanceM = 0.0, label = "Entrance", captureTMs = 0L),
            RouteNode(
                id = "n1", type = NodeType.TURN, atDistanceM = 20.0, label = "pillar",
                turn = TurnDir.RIGHT, cue = "Turn right at the pillar", captureTMs = 40_000L,
            ),
            RouteNode(
                id = "n2", type = NodeType.DESTINATION, atDistanceM = 45.0, label = "Elevator",
                cue = "Arrive at Elevator", captureTMs = 60_000L,
            ),
        ),
        legs = listOf(RouteLeg("n0", "n1", 20.0, 0.0), RouteLeg("n1", "n2", 25.0, 90.0)),
        path = listOf(PathPoint(0.0, 0.0, 0.0), PathPoint(45.0, 15.0, 20.0)),
    )

    private fun processingVm(): Pair<ProcessingViewModel, FakeCloudApi> {
        val dir = Files.createTempDirectory("w8-09-processing").toString().toPath()
        val drafts = DraftRepository(FileStore(FileSystem.SYSTEM, dir))
        drafts.saveDraftRoute(draftRoute())
        drafts.saveCapture(
            CaptureLog(
                draftId = "draft-1",
                startedAtEpochMs = 1_762_000_000_000L,
                motionSource = "echo_builtin_pdr",
                videoStartNs = 0L,
                samples = listOf(
                    CaptureSample(tNs = 0L, d = 0.0, yaw = 0.0),
                    CaptureSample(tNs = 40_000_000_000L, d = 20.0, yaw = 0.0),
                    CaptureSample(tNs = 60_000_000_000L, d = 45.0, yaw = 1.5),
                ),
                marks = emptyList(),
                startPhoto = "start.jpg",
                routeName = "Test walk",
                destinationLabel = "Elevator",
            ),
        )
        val draftDir = dir.resolve("drafts/draft-1")
        FileSystem.SYSTEM.createDirectories(draftDir)
        FileSystem.SYSTEM.write(draftDir.resolve("start.jpg")) { write("photo".encodeToByteArray()) }
        FileSystem.SYSTEM.write(draftDir.resolve("video.mp4")) { write("video".encodeToByteArray()) }
        val api = FakeCloudApi()
        val vm = ProcessingViewModel(
            draftId = "draft-1",
            drafts = drafts,
            media = FakeMedia(),
            api = api,
            nowMs = { 1_762_000_100_000L },
            pollIntervalMs = 1L,
            maxPolls = 10,
            ioDispatcher = Dispatchers.Unconfined,
        )
        return vm to api
    }

    // ── Processing: consent sheet first ───────────────────────────

    @Test
    fun processingConsentSheetShowsFrozenCopyBeforeAnyUpload() {
        val (vm, api) = processingVm()
        compose.waitUntil(timeoutMillis = 10_000) { vm.ui.value.phase is ProcessingPhase.Consent }

        compose.setContent {
            EchoTheme(darkTheme = true) {
                app.simt.echo.ui.screens.processing.ProcessingScreen(viewModel = vm, onDone = {})
            }
        }

        // The canopy consent sheet with the frozen privacy copy…
        compose.onNodeWithTag(ProcessingConsentSheetTag).assertIsDisplayed()
        compose.onNodeWithText("Before anything uploads").assertIsDisplayed()
        compose.onNodeWithText("deleted within 24 h", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Send", substring = false).assertIsDisplayed()
        // …and no staged instrument yet: nothing uploads before consent.
        compose.onNodeWithText("Reading scenes", substring = true).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(api.submits.isEmpty(), "no submit before the consent Send")
        }
    }

    // ── Share: QR first, legible, preview collapsed ───────────────

    @Test
    fun shareQrIsFirstReadLegibleAndPreviewCollapsed() {
        val service = shareService()
        compose.setContent {
            EchoTheme(darkTheme = true) {
                ShareScreen(
                    routeId = "w8-09-share-route",
                    service = service,
                    haptics = FakeHaptics(),
                    sharing = FakeSharing(),
                    onClose = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(ShareQrTag).fetchSemanticsNodes().isNotEmpty()
        }

        // The QR on its light surface at the frozen size: the surface
        // holds the frozen ≥260 dp minimum and the code fills it.
        compose.onNodeWithTag(ShareQrTag).assertIsDisplayed()
        compose.onNodeWithContentDescription("Pairing QR code").assertIsDisplayed()
        val qrPx = with(compose.density) { EchoQrMin.roundToPx() }
        val card = compose.onNodeWithTag(ShareQrTag).fetchSemanticsNode()
        assertTrue(
            card.size.height >= qrPx,
            "QR surface ${card.size.height}px below frozen ${qrPx}px (260dp)",
        )
        val qrSize = compose.onNodeWithTag(ShareQrImageTag).fetchSemanticsNode().size
        val padPx = with(compose.density) { 24.dp.roundToPx() }
        assertEquals(
            card.size.width - 2 * padPx,
            qrSize.width,
            "QR must fill its light surface edge to edge",
        )

        // First read: the QR sits above the collapsed export section…
        val qrTop = compose.onNodeWithTag(ShareQrTag).fetchSemanticsNode().boundsInRoot.top
        val exportTop = compose.onNodeWithTag(ShareExportSectionTag).fetchSemanticsNode().boundsInRoot.top
        assertTrue(qrTop < exportTop, "QR must read before the export preview")

        // …which stays collapsed until opened.
        compose.onNodeWithText("Show details").assertIsDisplayed()
        compose.onNodeWithText("Link expires", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(ShareExportToggleTag).performClick()
        compose.onNodeWithText("Link expires", substring = true).performScrollTo()
        compose.onNodeWithText("Link expires", substring = true).assertIsDisplayed()
    }

    // ── Receive: full-bleed scanner with reticle ──────────────────

    @Test
    fun receiveScannerIsFullBleedWithReticle() {
        compose.setContent {
            EchoTheme(darkTheme = true) {
                // 300 dp: wider than the old fixed 280 dp box, inside the
                // headless window — full-bleed must own every pixel of it.
                Column(modifier = Modifier.width(300.dp)) {
                    ReceiveScannerFrame {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag(ReceiveScannerTag).assertIsDisplayed()
        compose.onNodeWithTag(ReceiveReticleTag).assertIsDisplayed()
        // Full-bleed: the frame owns the whole parent width — never the
        // old fixed 280 dp box.
        val parentPx = with(compose.density) { 300.dp.roundToPx() }
        val frame = compose.onNodeWithTag(ReceiveScannerTag).fetchSemanticsNode()
        assertEquals(parentPx, frame.size.width, "scanner must fill its parent width")
        assertEquals(frame.size.width, frame.size.height, "scanner frame stays square")
    }
}
