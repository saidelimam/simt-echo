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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R1 gate (audit 0007, closed in W14-04): the LAN offer token comes from
 * the platform CSPRNG, never `kotlin.random.Random`. Properties only — a
 * CSPRNG has no deterministic vector to assert, so size, overload safety
 * and uniqueness stand in.
 */
class SecureRandomTest {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun returnsTheRequestedSize() {
        assertEquals(16, secureRandomBytes(16).size)
        assertEquals(32, secureRandomBytes(32).size)
        assertEquals(0, secureRandomBytes(0).size)
    }

    @Test
    fun consecutiveTokensDiffer() {
        val first = secureRandomBytes(QrPayload.TOKEN_BYTES)
        val second = secureRandomBytes(QrPayload.TOKEN_BYTES)
        assertFalse(first.contentEquals(second), "CSPRNG must not repeat a 128-bit token")
    }

    @Test
    fun batchOfTokensIsUnique() {
        val seen = mutableSetOf<String>()
        repeat(64) { seen += hex(secureRandomBytes(QrPayload.TOKEN_BYTES)) }
        assertEquals(64, seen.size, "64 CSPRNG tokens must all be distinct")
    }

    /**
     * Provenance pin: the production default token source is the platform
     * CSPRNG. Distinctness alone cannot tell it from `kotlin.random.Random`,
     * so the test names the function the service defaults to.
     */
    @Test
    fun serviceDefaultsToThePlatformCsprng() {
        assertEquals<Any>(::secureRandomBytes, TransferService.DEFAULT_RANDOM_BYTES)
    }

    @Test
    fun defaultOfferTokensAreUnique() = runTest {
        val fileSystem = FakeFileSystem()
        val store = FileStore(fileSystem, "/data".toPath())
        val routes = RouteRepository(store)
        routes.save(Fixtures.L_ROUTE)
        val transport = object : PeerTransport {
            override suspend fun offer(payload: ByteArray, token: String): Endpoint =
                Endpoint("192.168.1.20", 51234)

            override suspend fun fetch(endpoint: Endpoint, token: String): ByteArray =
                error("nothing served")

            override fun stopOffer() = Unit
        }
        val service = TransferService(transport = transport, routes = routes, store = store)
        val first = QrPayload.parse(service.offer(Fixtures.L_ROUTE.routeId).qrUri).getOrThrow()
        val second = QrPayload.parse(service.offer(Fixtures.L_ROUTE.routeId).qrUri).getOrThrow()
        assertTrue(!first.token.contentEquals(second.token), "default offers must mint distinct tokens")
    }
}
