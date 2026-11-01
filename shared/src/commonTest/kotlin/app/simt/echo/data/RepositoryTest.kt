package app.simt.echo.data

import app.simt.echo.Fixtures
import app.simt.echo.model.PrivacyInfo
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Repository gate on FakeFileSystem: save/list/delete, drafts, expiry. */
class RepositoryTest {

    private val fileSystem = FakeFileSystem()
    private val store = FileStore(fileSystem, "/data".toPath())
    private val routes = RouteRepository(store)
    private val drafts = DraftRepository(store)

    @Test
    fun saveListGetDeleteRoundTrip() {
        routes.save(Fixtures.L_ROUTE)
        assertEquals(listOf(Fixtures.L_ROUTE.routeId), routes.list().map { it.routeId })
        assertEquals(Fixtures.L_ROUTE, routes.get(Fixtures.L_ROUTE.routeId))
        routes.delete(Fixtures.L_ROUTE.routeId)
        assertTrue(routes.list().isEmpty())
        assertNull(routes.get(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun saveCopiesAssetsBesideRoute() {
        val source = "/camera/start.jpg".toPath()
        fileSystem.createDirectories("/camera".toPath())
        fileSystem.write(source) { write(byteArrayOf(1, 2, 3)) }
        routes.save(Fixtures.L_ROUTE, mapOf("a_start" to source))
        assertTrue(store.exists("routes/${Fixtures.L_ROUTE.routeId}/assets/a_start.jpg"))
        assertEquals(
            listOf<Byte>(1, 2, 3),
            store.readBytes("routes/${Fixtures.L_ROUTE.routeId}/assets/a_start.jpg")!!.toList(),
        )
    }

    @Test
    fun listSortsNewestFirstAndSkipsPartials() {
        val older = Fixtures.L_ROUTE.copy(routeId = "older", createdAtEpochMs = 10)
        val newer = Fixtures.L_ROUTE.copy(routeId = "newer", createdAtEpochMs = 20)
        routes.save(older)
        routes.save(newer)
        // A directory without route.json is a partial write; never listed.
        store.writeText("routes/ghost/readme.txt", "x")
        assertEquals(listOf("newer", "older"), routes.list().map { it.routeId })
    }

    @Test
    fun purgeExpiredDeletesOnlyExpiredRoutes() {
        val expired = Fixtures.L_ROUTE.copy(
            routeId = "expired",
            privacy = PrivacyInfo(expiresAtEpochMs = 100L),
        )
        val alive = Fixtures.L_ROUTE.copy(
            routeId = "alive",
            privacy = PrivacyInfo(expiresAtEpochMs = 10_000L),
        )
        routes.save(expired)
        routes.save(alive)
        routes.save(Fixtures.L_ROUTE)
        assertEquals(1, routes.purgeExpired(nowMs = 1_000L))
        assertEquals(
            setOf("alive", Fixtures.L_ROUTE.routeId),
            routes.list().map { it.routeId }.toSet(),
        )
    }

    @Test
    fun draftCaptureAndRouteRoundTrip() {
        val log = app.simt.echo.model.CaptureLog(
            draftId = "d1",
            startedAtEpochMs = 1L,
            motionSource = "echo_builtin_pdr",
            videoStartNs = 2L,
            samples = emptyList(),
            marks = emptyList(),
            startPhoto = "start.jpg",
        )
        drafts.saveCapture(log)
        drafts.saveDraftRoute(Fixtures.L_ROUTE)
        assertEquals(log, drafts.loadCapture("d1"))
        assertEquals(Fixtures.L_ROUTE, drafts.loadDraftRoute(Fixtures.L_ROUTE.routeId))
        assertTrue("d1" in drafts.listDraftIds())
        drafts.delete("d1")
        assertNull(drafts.loadCapture("d1"))
    }

    @Test
    fun fileStoreMissingPathsAreNullAndEmpty() {
        assertNull(store.readText("nope.txt"))
        assertNull(store.readBytes("nope.bin"))
        assertTrue(store.list("nope").isEmpty())
    }

    @Test
    fun fileStoreWriteCreatesParents() {
        store.writeText("deep/nested/file.txt", "hello")
        assertEquals("hello", store.readText("deep/nested/file.txt"))
        store.deleteRecursively("deep")
        assertNull(store.readText("deep/nested/file.txt"))
    }
}
