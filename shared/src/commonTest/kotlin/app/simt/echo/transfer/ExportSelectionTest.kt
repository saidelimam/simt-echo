package app.simt.echo.transfer

import app.simt.echo.Fixtures
import app.simt.echo.data.FileStore
import app.simt.echo.data.RouteRepository
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
