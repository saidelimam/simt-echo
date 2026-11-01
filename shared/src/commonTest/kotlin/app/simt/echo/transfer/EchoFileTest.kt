package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** W2-07 gate: plain `.echo` export/import round-trip; filename is safe. */
class EchoFileTest {

    private class FakeTransport : PeerTransport {
        override suspend fun offer(payload: ByteArray, token: String): Endpoint =
            Endpoint("192.168.1.20", 51234)

        override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
            error("file fallback never uses the transport")

        override fun stopOffer() = Unit
    }

    private fun setup(): Pair<FileStore, TransferService> {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val service = TransferService(
            transport = FakeTransport(),
            routes = routes,
            store = store,
            nowMs = { 1_762_000_000_000L },
        )
        return store to service
    }

    @Test
    fun exportWritesPlainContainerAndImportRoundTrips() = runTest {
        val (store, service) = setup()
        val fileName = service.echoFileName(Fixtures.L_ROUTE.name)
        val path = service.exportFile(Fixtures.L_ROUTE.routeId)

        // The file lands under exports/ and is a plain C container, not a seal.
        assertEquals(store.resolvePath("exports/$fileName"), path)
        val bytes = store.readBytes("exports/$fileName")!!
        assertEquals("ECHO".encodeToByteArray().toList(), bytes.take(4).toList())

        // Import decodes the same route back out.
        val body = service.importFile(bytes)
        assertEquals(Fixtures.L_ROUTE, body.route)
    }

    @Test
    fun importRejectsNonEchoBytes() = runTest {
        val (_, service) = setup()
        val failure = runCatching { service.importFile("not a bundle".encodeToByteArray()) }
        assertTrue(failure.isFailure)
    }

    @Test
    fun echoFileNameCollapsesPathHostileCharacters() {
        val (_, service) = setup()
        assertEquals("my_route.echo", service.echoFileName("my route"))
        assertEquals("a_b_c_d_e_f_g_h_i_j.echo", service.echoFileName("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("route.echo", service.echoFileName("   "))
    }
}
