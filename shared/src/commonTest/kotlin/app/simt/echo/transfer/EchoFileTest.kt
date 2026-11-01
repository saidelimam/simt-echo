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
        val path = service.exportFile(Fixtures.L_ROUTE.routeId)

        // The file lands under exports/ (route id in the name) and is a
        // plain C container, not a seal.
        assertTrue(path.name.endsWith(".echo"), path.name)
        assertTrue(path.name.contains(Fixtures.L_ROUTE.routeId), path.name)
        val bytes = store.readBytes(path.toString())!!
        assertEquals("ECHO".encodeToByteArray().toList(), bytes.take(4).toList())

        // Import decodes the same route back out.
        val body = service.importFile(bytes)
        assertEquals(Fixtures.L_ROUTE, body.route)
    }

    @Test
    fun photoBytesSurviveTheFileRoundTrip() = runTest {
        val (store, service) = setup()
        val routes = RouteRepository(store)
        val photo = byteArrayOf(9, 8, 7, 6)
        routes.saveWithAssetBytes(
            Fixtures.L_ROUTE,
            mapOf(Fixtures.L_ROUTE.start.photoAssetId!! to photo),
        )
        val path = service.exportFile(Fixtures.L_ROUTE.routeId)
        val body = service.importFile(store.readBytes(path.toString())!!)

        assertEquals(1, body.assets.size)
        assertEquals(
            photo.toList(),
            kotlin.io.encoding.Base64.decode(body.assets.single().b64).toList(),
        )

        // And Save persists the imported body for real.
        service.saveImported(body)
        assertEquals(
            photo.toList(),
            routes.readAsset(Fixtures.L_ROUTE.routeId, Fixtures.L_ROUTE.start.photoAssetId!!)!!.toList(),
        )
    }

    @Test
    fun traversalIdsAreRejectedOnSave() = runTest {
        val (store, service) = setup()
        val bytes = store.readBytes(service.exportFile(Fixtures.L_ROUTE.routeId).toString())!!
        val body = service.importFile(bytes)

        val evilRoute = body.copy(route = body.route.copy(routeId = "../../evil"))
        assertTrue(runCatching { service.saveImported(evilRoute) }.isFailure)

        val evilAsset = body.copy(
            assets = listOf(app.simt.echo.model.Asset(id = "../../evil.jpg", mime = "image/jpeg", b64 = "AA==")),
        )
        assertTrue(runCatching { service.saveImported(evilAsset) }.isFailure)
        // Nothing escaped into a sibling directory.
        assertTrue(store.list("evil").isEmpty())
    }

    @Test
    fun expiredAndOversizeFilesAreRefused() = runTest {
        val (store, service) = setup()
        val routes = RouteRepository(store)
        routes.save(
            Fixtures.L_ROUTE.copy(
                routeId = "old-route",
                privacy = app.simt.echo.model.PrivacyInfo(expiresAtEpochMs = 10L),
            ),
        )
        val expiredPath = run {
            val s = TransferService(FakeTransport(), routes, store, nowMs = { 1L })
            s.exportFile("old-route")
        }
        val bytes = store.readBytes(expiredPath.toString())!!
        // The test clock (1.76e12) is long past the 10 ms expiry.
        assertTrue(runCatching { service.importFile(bytes) }.isFailure)

        assertTrue(
            runCatching { service.importFile(ByteArray(50_000_001)) }.isFailure,
        )
    }

    @Test
    fun duplicateImportReplacesCleanlyAndExportsPurge() = runTest {
        val (store, service) = setup()
        val routes = RouteRepository(store)
        routes.saveWithAssetBytes(
            Fixtures.L_ROUTE,
            mapOf("stale_asset" to byteArrayOf(1)),
        )
        val body = service.importFile(store.readBytes(service.exportFile(Fixtures.L_ROUTE.routeId).toString())!!)
        service.saveImported(body)
        service.saveImported(body) // re-import: no stale assets survive
        assertEquals(null, routes.readAsset(Fixtures.L_ROUTE.routeId, "stale_asset"))

        assertTrue(store.exists("exports"))
        service.purgePlainExports()
        assertTrue(store.list("exports").isEmpty())
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
