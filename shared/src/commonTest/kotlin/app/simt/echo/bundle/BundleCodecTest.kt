package app.simt.echo.bundle

import app.simt.echo.Fixtures
import app.simt.echo.model.Asset
import app.simt.echo.model.PrivacyInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** W2-02 gate: container, seal/open, tamper, wrong key, expiry. */
class BundleCodecTest {

    private val assets = listOf(
        Asset(id = "a_start", mime = "image/jpeg", b64 = "AAAA"),
        Asset(id = "a_lm", mime = "image/jpeg", b64 = "BBBB"),
    )

    private fun errorOf(result: Result<*>): BundleError =
        (result.exceptionOrNull() as BundleCodecException).error

    @Test
    fun plainContainerRoundTripsAndSortsAssets() = runTest {
        val shuffled = listOf(assets[1], assets[0])
        val container = BundleCodec.encode(Fixtures.L_ROUTE, shuffled)
        // Header: magic + version (body follows the 32-byte hash).
        assertEquals("ECHO", container.copyOfRange(0, 4).decodeToString())
        assertEquals(1, container[4].toInt())
        val opened = BundleCodec.open(container).getOrThrow()
        assertEquals(Fixtures.L_ROUTE, opened.route)
        assertEquals(listOf("a_lm", "a_start"), opened.assets.map { it.id })
    }

    @Test
    fun sealedRoundTripsWithKey() = runTest {
        val container = BundleCodec.encode(Fixtures.L_ROUTE, assets)
        val (sealed, key) = BundleCodec.seal(container)
        assertEquals("ECX1", sealed.copyOfRange(0, 4).decodeToString())
        assertEquals(32, key.size) // AES-256
        val opened = BundleCodec.open(sealed, key).getOrThrow()
        assertEquals(Fixtures.L_ROUTE, opened.route)
    }

    @Test
    fun sealedWithoutKeyNeedsKey() = runTest {
        val (sealed, _) = BundleCodec.seal(BundleCodec.encode(Fixtures.L_ROUTE, assets))
        assertEquals(BundleError.NEEDS_KEY, errorOf(BundleCodec.open(sealed)))
    }

    @Test
    fun wrongKeyFailsDecrypt() = runTest {
        val (sealed, _) = BundleCodec.seal(BundleCodec.encode(Fixtures.L_ROUTE, assets))
        val wrongKey = ByteArray(32) { 0x2A }
        assertEquals(BundleError.DECRYPT_FAILED, errorOf(BundleCodec.open(sealed, wrongKey)))
    }

    @Test
    fun flippedBodyByteFailsHash() = runTest {
        val container = BundleCodec.encode(Fixtures.L_ROUTE, assets)
        val tampered = container.copyOf()
        val last = tampered.lastIndex
        tampered[last] = (tampered[last] + 1).toByte()
        assertEquals(BundleError.HASH_MISMATCH, errorOf(BundleCodec.open(tampered)))
    }

    @Test
    fun flippedCipherByteFailsDecrypt() = runTest {
        val (sealed, key) = BundleCodec.seal(BundleCodec.encode(Fixtures.L_ROUTE, assets))
        val tampered = sealed.copyOf()
        tampered[tampered.lastIndex] = (tampered[tampered.lastIndex] + 1).toByte()
        assertEquals(BundleError.DECRYPT_FAILED, errorOf(BundleCodec.open(tampered, key)))
    }

    @Test
    fun garbageIsBadMagic() = runTest {
        assertEquals(BundleError.BAD_MAGIC, errorOf(BundleCodec.open(byteArrayOf(1, 2, 3, 4, 5))))
        assertEquals(BundleError.BAD_MAGIC, errorOf(BundleCodec.open("NOPE....".encodeToByteArray())))
    }

    @Test
    fun unknownVersionIsRejected() = runTest {
        val container = BundleCodec.encode(Fixtures.L_ROUTE, assets)
        val future = container.copyOf()
        future[4] = 0x7F
        assertEquals(BundleError.UNSUPPORTED_VERSION, errorOf(BundleCodec.open(future)))
    }

    @Test
    fun expiredRouteIsRejectedOnlyWhenClockGiven() = runTest {
        val expired = Fixtures.L_ROUTE.copy(privacy = PrivacyInfo(expiresAtEpochMs = 1_000L))
        val container = BundleCodec.encode(expired, assets)
        // No clock: codec does not judge expiry (receiver's purge does).
        assertTrue(BundleCodec.open(container).isSuccess)
        assertEquals(
            BundleError.EXPIRED,
            errorOf(BundleCodec.open(container, nowMs = 1_000L)),
        )
        assertTrue(BundleCodec.open(container, nowMs = 999L).isSuccess)
    }

    @Test
    fun largeBundleRoundTripsFast() = runTest {
        // ~500 KB of base64 payload (6 photo crops at bundle scale).
        val big = Asset(id = "a_big", mime = "image/jpeg", b64 = "A".repeat(500_000))
        val mark = TimeSource.Monotonic.markNow()
        val container = BundleCodec.encode(Fixtures.L_ROUTE, listOf(big))
        val opened = BundleCodec.open(container).getOrThrow()
        val elapsedMs = mark.elapsedNow().inWholeMilliseconds
        assertEquals(big, opened.assets.single())
        // Spec target is < 100 ms on device; host gate stays lenient (2 s)
        // so CI load cannot flake it — the shape, not the stopwatch, is gated.
        assertTrue(elapsedMs < 2_000, "encode+open took ${elapsedMs}ms")
    }
}
