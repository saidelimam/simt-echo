package app.simt.echo.data

import app.simt.echo.Fixtures
import app.simt.echo.model.PrivacyInfo
import app.simt.echo.model.ViewStrip
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W18-02 gates: follower-side RouteMemory overlay persistence, arrival-σ and
 * visual-lock gating, 3-strip-per-waypoint LRU eviction, route expiry inheritance,
 * route and Delete-all erasure paths, and corrupted-entry resilience.
 */
class RouteMemoryRepositoryTest {

    private fun strip(distM: Double, fill: Int): ViewStrip =
        ViewStrip(
            atDistanceM = distM,
            dataB64 = Base64.encode(ByteArray(ViewStrip.BYTES) { (fill + it).toByte() }),
        )

    @Test
    fun gatesRequireArrivalSigmaUnderTwoMetresAndSixtyPercentVisualLock() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val memory = RouteMemoryRepository(store)
        val route = Fixtures.L_ROUTE
        routes.save(route)

        val candidates = listOf(strip(10.0, 1))

        // Fails arrival sigma >= 2.0 m
        assertFalse(memory.recordFollow(route, arrivalSigmaM = 2.0, visualLockRatio01 = 0.80, observedStrips = candidates, nowMs = 1_000L))
        assertNull(memory.get(route.routeId, nowMs = 1_000L))

        // Fails visual lock < 60%
        assertFalse(memory.recordFollow(route, arrivalSigmaM = 1.2, visualLockRatio01 = 0.59, observedStrips = candidates, nowMs = 1_000L))
        assertNull(memory.get(route.routeId, nowMs = 1_000L))

        // Passes both gates: stored locally without rewriting route.json.
        val routeBefore = routes.get(route.routeId)
        assertTrue(memory.recordFollow(route, arrivalSigmaM = 1.4, visualLockRatio01 = 0.65, observedStrips = candidates, nowMs = 1_000L))
        assertEquals(routeBefore, routes.get(route.routeId), "route.json must never be rewritten by RouteMemory")
        assertEquals(1, memory.loadOverlayStrips(route.routeId, nowMs = 1_000L).size)
    }

    @Test
    fun lruEvictsOldestBeyondThreeStripsPerWaypoint() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val memory = RouteMemoryRepository(store)
        val route = Fixtures.L_ROUTE
        routes.save(route)

        val s1 = strip(20.0, 10)
        val s2 = strip(20.05, 20) // same 0.5 m waypoint bucket (20.0 m)
        val s3 = strip(19.95, 30)
        val s4 = strip(20.0, 40)
        val otherWaypoint = strip(32.0, 50)

        assertTrue(memory.recordFollow(route, 1.0, 0.8, listOf(s1), nowMs = 100L))
        assertTrue(memory.recordFollow(route, 1.0, 0.8, listOf(s2), nowMs = 200L))
        assertTrue(memory.recordFollow(route, 1.0, 0.8, listOf(s3, otherWaypoint), nowMs = 300L))
        assertTrue(memory.recordFollow(route, 1.0, 0.8, listOf(s4), nowMs = 400L))

        val loaded = assertNotNull(memory.get(route.routeId, nowMs = 500L))
        val at20 = loaded.entries.filter { it.atDistanceM == 20.0 }
        val at32 = loaded.entries.filter { it.atDistanceM == 32.0 }

        assertEquals(3, at20.size, "at most 3 strips per waypoint")
        assertEquals(listOf(200L, 300L, 400L), at20.map { it.recordedAtEpochMs }, "oldest entry (100L) must be evicted by LRU")
        assertEquals(listOf(s2.dataB64, s3.dataB64, s4.dataB64), at20.map { it.dataB64 })
        assertEquals(1, at32.size)
    }

    @Test
    fun expiryAndDeletionPathsRemoveOverlayWithRouteAndDeleteAll() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val memory = RouteMemoryRepository(store)

        val expiringRoute = Fixtures.L_ROUTE.copy(
            routeId = "route-expiring",
            privacy = PrivacyInfo(expiresAtEpochMs = 5_000L),
        )
        val secondRoute = Fixtures.L_ROUTE.copy(routeId = "route-second")
        routes.save(expiringRoute)
        routes.save(secondRoute)

        assertTrue(memory.recordFollow(expiringRoute, 1.1, 0.75, listOf(strip(10.0, 1)), nowMs = 2_000L))
        assertTrue(memory.recordFollow(secondRoute, 1.1, 0.75, listOf(strip(12.0, 2)), nowMs = 2_000L))

        // Before expiry: readable and carries exact route expiry.
        val beforeExpiry = assertNotNull(memory.get("route-expiring", nowMs = 4_999L))
        assertEquals(5_000L, beforeExpiry.expiresAtEpochMs)
        assertEquals(1, memory.loadOverlayStrips("route-expiring", nowMs = 4_999L).size)

        // At/after expiry: purged and unreadable.
        assertNull(memory.get("route-expiring", nowMs = 5_000L))
        assertTrue(memory.loadOverlayStrips("route-expiring", nowMs = 5_000L).isEmpty())

        // Re-record on secondRoute, then delete the route through RouteRepository: overlay is gone.
        routes.delete("route-second")
        assertNull(memory.get("route-second", nowMs = 2_000L))

        // Re-save and verify RouteRepository.deleteAll() (Delete all data) removes all overlays.
        routes.save(secondRoute)
        assertTrue(memory.recordFollow(secondRoute, 1.0, 0.9, listOf(strip(8.0, 3)), nowMs = 2_000L))
        assertEquals(1, memory.loadOverlayStrips("route-second", nowMs = 2_000L).size)
        routes.deleteAll()
        assertTrue(memory.loadOverlayStrips("route-second", nowMs = 2_000L).isEmpty())
    }

    @Test
    fun corruptedOverlayEntriesAndCorruptJsonAreIgnoredWithoutCrashing() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val memory = RouteMemoryRepository(store)
        val route = Fixtures.L_ROUTE
        routes.save(route)

        // 1. Corrupt JSON file -> returns null/empty, never throws.
        store.writeText("routes/${route.routeId}/memory.json", "{ not valid json !!!")
        assertNull(memory.get(route.routeId, nowMs = 1_000L))
        assertTrue(memory.loadOverlayStrips(route.routeId, nowMs = 1_000L).isEmpty())

        // 2. Mix of valid and corrupted entries inside valid JSON -> keeps only the valid entry.
        val good = strip(14.0, 9)
        val mixedJson = """
            {
              "routeId": "${route.routeId}",
              "expiresAtEpochMs": null,
              "entries": [
                {"atDistanceM": -5.0, "dataB64": "${good.dataB64}", "recordedAtEpochMs": 100},
                {"atDistanceM": 10.0, "dataB64": "not-base64!!!", "recordedAtEpochMs": 200},
                {"atDistanceM": 12.0, "dataB64": "AAAA", "recordedAtEpochMs": 300},
                {"atDistanceM": 14.0, "dataB64": "${good.dataB64}", "recordedAtEpochMs": 400}
              ]
            }
        """.trimIndent()
        store.writeText("routes/${route.routeId}/memory.json", mixedJson)
        val strips = memory.loadOverlayStrips(route.routeId, nowMs = 1_000L)
        assertEquals(listOf(good), strips)
    }
}
