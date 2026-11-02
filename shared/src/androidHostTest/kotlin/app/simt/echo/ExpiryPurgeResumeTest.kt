package app.simt.echo

import app.simt.echo.model.EchoRoute
import app.simt.echo.model.PrivacyInfo
import app.simt.echo.model.StartAnchor
import app.simt.echo.platform.AndroidPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

/**
 * R3 gate (audit 0007, closed in W14-04): the resume entry point
 * ([AppGraph.purgeExpiredRoutes], called from `MainActivity.onResume`)
 * purges routes that expired while the app sat in the background and
 * keeps live ones — the same seam `EchoApplication.onCreate` uses.
 */
class ExpiryPurgeResumeTest {

    private fun route(id: String, expiry: Long?): EchoRoute = EchoRoute(
        routeId = id,
        name = id,
        createdAtEpochMs = 1L,
        creatorLabel = "purge-test",
        motionSource = "echo_builtin_pdr",
        totalLengthM = 1.0,
        start = StartAnchor(description = "start", photoAssetId = null, alignmentHint = ""),
        nodes = emptyList(),
        legs = emptyList(),
        path = emptyList(),
        privacy = PrivacyInfo(expiresAtEpochMs = expiry),
    )

    @Test
    fun resumePurgeRemovesExpiredAndKeepsLive() {
        val dir = Files.createTempDirectory("echo-purge").toFile()
        try {
            val graph = AppGraph(platform = AndroidPlatform(dataDir = dir.absolutePath))
            graph.routeRepository.save(route("expired", expiry = 100L))
            graph.routeRepository.save(route("live", expiry = 10_000L))
            graph.routeRepository.save(route("no-expiry", expiry = null))

            // Saved live, expired while backgrounded: the resume purge
            // removes exactly the expired route.
            assertEquals(1, graph.purgeExpiredRoutes(nowMs = 1_000L))
            assertNull(graph.routeRepository.get("expired"))
            assertNotNull(graph.routeRepository.get("live"))
            assertNotNull(graph.routeRepository.get("no-expiry"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
