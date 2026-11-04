package app.simt.echo

import app.simt.echo.capture.FaceScanner
import app.simt.echo.platform.AndroidPlatform
import app.simt.echo.transfer.FakeStripMedia
import app.simt.echo.transfer.NetworkGate
import app.simt.echo.transfer.NetworkKind
import app.simt.echo.transfer.OfflineNetworkGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okio.fakefilesystem.FakeFileSystem
import java.nio.file.Files

/**
 * W11-09 binding: the walk-time consent follows the persisted Settings
 * switch, read at grant time, and an unwired graph never sees a network.
 */
class AppGraphReadyConsentTest {

    private inline fun withGraph(
        gate: NetworkGate? = null,
        cloud: Boolean = false,
        scanner: FaceScanner? = null,
        block: (AppGraph) -> Unit,
    ) {
        val dir = Files.createTempDirectory("echo-ready").toFile()
        try {
            val platform = AndroidPlatform(dataDir = dir.absolutePath)
            block(
                AppGraph(
                    platform = platform,
                    apiBaseUrl = if (cloud) "https://worker.test" else "",
                    networkGate = gate ?: OfflineNetworkGate,
                    faceScanner = scanner,
                ),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun consentAllowsMobileDataUntilTheSettingIsOff() = withGraph { graph ->
        assertTrue(graph.readyUploadConsent().enabled)
        assertFalse("a fresh install may use mobile data", graph.readyUploadConsent().wifiOnly)
        assertTrue(graph.readyUploadConsent().allows(NetworkKind.METERED))
        assertFalse("never while roaming", graph.readyUploadConsent().allows(NetworkKind.ROAMING))

        graph.configRepository.update { it.copy(readyUploadMobileData = false) }
        assertTrue("a Settings change applies to the next consent", graph.readyUploadConsent().wifiOnly)

        graph.configRepository.update { it.copy(readyUploadMobileData = true) }
        assertFalse(graph.readyUploadConsent().wifiOnly)
    }

    @Test
    fun anUnwiredGraphFailsClosedOffline() = withGraph { graph ->
        assertEquals(NetworkKind.OFFLINE, graph.networkGate.kind())
    }

    @Test
    fun theWiredGateIsTheOneTheGraphExposes() = withGraph(NetworkGate { NetworkKind.METERED }) { graph ->
        assertEquals(NetworkKind.METERED, graph.networkGate.kind())
    }

    private val media = FakeStripMedia(FakeFileSystem())

    @Test
    fun noWalkStagerWithoutCloudCredentials() = withGraph(scanner = FaceScanner { 0 }) { graph ->
        assertNull(graph.walkStager("draft-1", media, { null }, { emptyList() }))
    }

    @Test
    fun noWalkStagerWithoutAFaceFilterBecauseTheConsentPromisesOne() = withGraph(cloud = true) { graph ->
        assertNull(graph.walkStager("draft-1", media, { null }, { emptyList() }))
    }

    @Test
    fun aConfiguredFaceFilteredGraphBuildsAWalkStager() = withGraph(cloud = true, scanner = FaceScanner { 0 }) { graph ->
        assertNotNull(graph.walkStager("draft-1", media, { null }, { emptyList() }))
    }
}
