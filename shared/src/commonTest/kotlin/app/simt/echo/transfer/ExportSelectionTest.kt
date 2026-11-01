package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
import app.simt.echo.model.AiProvenance
import app.simt.echo.model.Confirmation
import app.simt.echo.model.Landmark
import app.simt.echo.model.LandmarkSource
import app.simt.echo.model.PrivacyInfo
import app.simt.echo.platform.Endpoint
import app.simt.echo.platform.PeerTransport
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W3-08 gate: preview defaults, selection filtering, expiry, purge. */
class ExportSelectionTest {

    private fun riskyRoute() = Fixtures.L_ROUTE.copy(
        landmarks = listOf(
            Landmark(
                id = "lm-risky",
                nodeId = "n1",
                title = "Face on poster",
                description = "",
                cue = "Turn",
                cropAssetId = "kf/risky.jpg",
                privacyRisk = true,
                source = LandmarkSource.MANUAL,
                confirmation = Confirmation.CREATOR_VERIFIED,
            ),
            Landmark(
                id = "lm-safe",
                nodeId = "n2",
                title = "Red pillar",
                description = "",
                cue = "Turn",
                cropAssetId = "kf/safe.jpg",
                source = LandmarkSource.MANUAL,
                confirmation = Confirmation.CREATOR_VERIFIED,
            ),
        ),
    )

    @Test
    fun previewListsStartPhotoAndCropsWithRiskyDefaultOff() {
        val items = describeExport(riskyRoute())
        assertEquals(3, items.size)
        val start = items.single { it.id == START_PHOTO_ITEM_ID }
        assertTrue(start.includedByDefault)
        val risky = items.single { it.id == "kf/risky.jpg" }
        assertTrue(risky.privacyRisk)
        assertFalse(risky.includedByDefault)
        val safe = items.single { it.id == "kf/safe.jpg" }
        assertFalse(safe.privacyRisk)
        assertTrue(safe.includedByDefault)
    }

    @Test
    fun defaultSelectionExcludesOnlyRiskyCrops() {
        val selection = defaultSelection(riskyRoute())
        assertTrue(selection.includeStartPhoto)
        assertEquals(setOf("kf/risky.jpg"), selection.excludedCropIds)
        assertEquals(ExpiryOption.NONE, selection.expiry)
    }

    @Test
    fun applySelectionNullsExcludedCropsAndSetsExpiry() {
        val route = riskyRoute()
        val filtered = applySelection(
            route,
            ExportSelection(excludedCropIds = setOf("kf/risky.jpg"), expiry = ExpiryOption.HOURS_24),
            nowMs = 1_762_000_000_000L,
        )
        assertNull(filtered.landmarks.single { it.id == "lm-risky" }.cropAssetId)
        assertEquals("kf/safe.jpg", filtered.landmarks.single { it.id == "lm-safe" }.cropAssetId)
        assertNotNull(filtered.start.photoAssetId)
        assertEquals(1_762_000_000_000L + HOURS_24_MS, filtered.privacy.expiresAtEpochMs)
    }

    @Test
    fun applySelectionCanDropStartPhotoAndUseSevenDays() {
        val filtered = applySelection(
            riskyRoute(),
            ExportSelection(includeStartPhoto = false, expiry = ExpiryOption.DAYS_7),
            nowMs = 1_000L,
        )
        assertNull(filtered.start.photoAssetId)
        assertEquals(1_000L + DAYS_7_MS, filtered.privacy.expiresAtEpochMs)
    }

    @Test
    fun noExpiryLeavesPrivacyUnset() {
        val filtered = applySelection(riskyRoute(), ExportSelection(), nowMs = 1_000L)
        assertNull(filtered.privacy.expiresAtEpochMs)
    }

    @Test
    fun toggledOffCropsAreAbsentFromReceivedBundle() = runTest {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        val route = riskyRoute()
        fileSystem.createDirectories("/camera".toPath())
        val startSrc = "/camera/start.jpg".toPath()
        val riskySrc = "/camera/risky.jpg".toPath()
        val safeSrc = "/camera/safe.jpg".toPath()
        fileSystem.write(startSrc) { write(byteArrayOf(1)) }
        fileSystem.write(riskySrc) { write(byteArrayOf(2)) }
        fileSystem.write(safeSrc) { write(byteArrayOf(3)) }
        routes.save(
            route,
            mapOf("a_start" to startSrc, "kf/risky.jpg" to riskySrc, "kf/safe.jpg" to safeSrc),
        )
        val transport = object : PeerTransport {
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
        val sender = TransferService(
            transport = transport,
            routes = routes,
            store = store,
            randomBytes = { size -> ByteArray(size) { (it + 1).toByte() } },
            nowMs = { 1_762_000_000_000L },
        )
        // Default selection: the risky crop is OFF.
        val offer = sender.offer(route.routeId, selection = defaultSelection(route))

        val storeB = FileStore(FakeFileSystem(), "/phoneB".toPath())
        val phoneB = RouteRepository(storeB)
        val receiver = TransferService(transport, phoneB, storeB, nowMs = { 1_762_000_000_000L })
        val received = receiver.receive(offer.qrUri)

        assertEquals(listOf("a_start", "kf/safe.jpg"), received.assets.map { it.id }.sorted())
        assertNull(received.route.landmarks.single { it.id == "lm-risky" }.cropAssetId)
        assertEquals("kf/safe.jpg", received.route.landmarks.single { it.id == "lm-safe" }.cropAssetId)
        // The persisted import matches: nothing risky on disk on B.
        assertNull(phoneB.readAsset(route.routeId, "kf/risky.jpg"))
        assertNotNull(phoneB.readAsset(route.routeId, "kf/safe.jpg"))
    }

    @Test
    fun summaryCoversEverythingMaterial() {
        val route = riskyRoute().copy(
            ai = AiProvenance("job-42", "scout-v1", "ground-v2", 1_762_000_000_500L),
            privacy = PrivacyInfo(expiresAtEpochMs = 1_762_999_999_999L),
            landmarks = riskyRoute().landmarks.map {
                if (it.id == "lm-safe") it.copy(signText = "Exit") else it
            },
        )
        val selection = ExportSelection(excludedCropIds = setOf("kf/safe.jpg"), expiry = ExpiryOption.KEEP_STORED)
        val summary = exportSummary(route, selection, nowMs = 1_762_000_000_000L)
        assertEquals(route.name, summary.routeName)
        assertEquals(route.creatorLabel, summary.creatorLabel)
        assertEquals(route.createdAtEpochMs, summary.createdAtEpochMs)
        assertEquals(route.nodes.size, summary.nodeCount)
        assertEquals(route.path.size, summary.pathPointCount)
        assertEquals(route.totalLengthM, summary.totalLengthM)
        assertEquals(route.start.description, summary.startDescription)
        assertTrue(summary.hasAlignmentHint)
        assertTrue(summary.hasStartPhoto)
        assertEquals(2, summary.landmarks.size)
        val safe = summary.landmarks.single { it.id == "lm-safe" }
        assertEquals("Red pillar", safe.title)
        assertEquals("Turn", safe.cue)
        assertEquals("Exit", safe.signText)
        assertTrue(safe.hasCrop)
        assertFalse(safe.cropIncluded)
        val risky = summary.landmarks.single { it.id == "lm-risky" }
        assertTrue(risky.privacyRisk)
        assertEquals("job-42", summary.ai?.jobId)
        assertEquals("scout-v1", summary.ai?.scoutModel)
        assertEquals("ground-v2", summary.ai?.groundModel)
        assertEquals(1_762_999_999_999L, summary.expiresAtEpochMs)
    }

    @Test
    fun applySelectionDropsAiSuggestedLandmarksEntirely() {
        val route = riskyRoute().copy(
            landmarks = riskyRoute().landmarks + Landmark(
                id = "lm-ai",
                nodeId = "n1",
                title = "Unreviewed",
                description = "",
                cue = "Turn",
                signText = "Secret sign",
                cropAssetId = "kf/ai.jpg",
                source = LandmarkSource.AI_GROUNDED,
                confirmation = Confirmation.AI_SUGGESTED,
            ),
        )
        val filtered = applySelection(route, ExportSelection(), nowMs = 0L)
        assertTrue(filtered.landmarks.none { it.id == "lm-ai" })
        assertTrue(filtered.nodes.all { "lm-ai" !in it.landmarkIds })
        // Summary must not list it either.
        assertTrue(exportSummary(route, ExportSelection(), 0L).landmarks.none { it.id == "lm-ai" })
    }

    @Test
    fun reExportPreservesStoredExpiryByDefault() {
        val route = riskyRoute().copy(privacy = PrivacyInfo(expiresAtEpochMs = 9_999_999_999_999L))
        val selection = defaultSelection(route)
        assertEquals(ExpiryOption.KEEP_STORED, selection.expiry)
        val filtered = applySelection(route, selection, nowMs = 1_000L)
        assertEquals(9_999_999_999_999L, filtered.privacy.expiresAtEpochMs)
    }

    @Test
    fun expiredRouteCannotBeOfferedOrExported() = runTest {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val expired = Fixtures.L_ROUTE.copy(privacy = PrivacyInfo(expiresAtEpochMs = 1_000L))
        routes.save(expired)
        val transport = object : PeerTransport {
            override suspend fun offer(payload: ByteArray, token: String): Endpoint = Endpoint("192.168.1.20", 51234)
            override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray = error("unused")
            override fun stopOffer() = Unit
        }
        val service = TransferService(transport, routes, store, nowMs = { 2_000L })
        assertFailsWith<RouteExpiredException> { service.offer(expired.routeId) }
        assertFailsWith<RouteExpiredException> { service.exportFile(expired.routeId) }
    }

    @Test
    fun deleteRemovesPlainExportsAndJobIdRecord() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        store.writeBytes("exports/L_route_-fixture-l-route.echo", byteArrayOf(1))
        store.writeBytes("exports/Other-other.echo", byteArrayOf(2))
        val transport = object : PeerTransport {
            override suspend fun offer(payload: ByteArray, token: String): Endpoint = Endpoint("h", 1)
            override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray = error("unused")
            override fun stopOffer() = Unit
        }
        val service = TransferService(transport, routes, store, nowMs = { 0L })
        service.deletePlainExportsFor(Fixtures.L_ROUTE.routeId)
        assertNull(store.readBytes("exports/L_route_-fixture-l-route.echo"))
        assertNotNull(store.readBytes("exports/Other-other.echo"))

        routes.saveRouteJobId(Fixtures.L_ROUTE.routeId, "job-7")
        assertEquals("job-7", routes.routeJobId(Fixtures.L_ROUTE.routeId))
        routes.clearRouteJobId(Fixtures.L_ROUTE.routeId)
        assertNull(routes.routeJobId(Fixtures.L_ROUTE.routeId))
        routes.saveRouteJobId(Fixtures.L_ROUTE.routeId, "job-8")
        routes.delete(Fixtures.L_ROUTE.routeId)
        assertNull(routes.routeJobId(Fixtures.L_ROUTE.routeId))
    }

    @Test
    fun expiredRoutesPurgeOnRestartWhileLiveOnesStay() {
        val store = FileStore(FakeFileSystem(), "/data".toPath())
        val routes = RouteRepository(store)
        val expired = Fixtures.L_ROUTE.copy(
            routeId = "old",
            privacy = PrivacyInfo(expiresAtEpochMs = 1_000L),
        )
        val live = Fixtures.L_ROUTE.copy(
            routeId = "fresh",
            privacy = PrivacyInfo(expiresAtEpochMs = 9_999_999_999_999L),
        )
        val forever = Fixtures.L_ROUTE.copy(routeId = "forever")
        routes.save(expired)
        routes.save(live)
        routes.save(forever)

        assertEquals(1, routes.purgeExpired(nowMs = 2_000L))
        assertNull(routes.get("old"))
        assertNotNull(routes.get("fresh"))
        assertNotNull(routes.get("forever"))
    }
}
