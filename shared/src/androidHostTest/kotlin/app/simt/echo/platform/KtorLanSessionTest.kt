package app.simt.echo.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * W20-03 gate for the real LAN server (plan 0005 §4.5): replacement must
 * not be undone by the offer it replaced.
 *
 * The old transport armed a three-second shutdown per download and a
 * ten-minute one per offer, both calling an unscoped `stopOffer()`. After a
 * re-offer those timers killed whichever server was newest. These tests
 * drive an actual CIO server and a real HTTP request, because the hazard is
 * a lifecycle one that a fake cannot reproduce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class KtorLanSessionTest {

    private data class Reply(val status: Int, val body: ByteArray)

    private fun get(endpoint: Endpoint, token: String): Reply {
        val connection = URL("http://${endpoint.host}:${endpoint.port}/echo/$token")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        return try {
            val status = connection.responseCode
            val stream = if (status == 200) connection.inputStream else connection.errorStream
            Reply(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun anOldOffersShutdownTimerCannotStopANewerOffer() = runBlocking {
        val transport = KtorLanTransport(afterDownloadStopMs = 50, offerLifetimeMs = 60_000)
        try {
            val first = transport.offer(ByteArray(64) { 1 }, "token-one", 1L)
            assertEquals(200, get(first, "token-one").status)

            // Re-offer immediately: the first server is replaced while its
            // 50 ms post-download shutdown is still armed.
            val second = transport.offer(ByteArray(96) { 2 }, "token-two", 2L)
            delay(400.milliseconds)

            val reply = get(second, "token-two")
            assertEquals(200, reply.status, "the new offer must still be serving")
            assertEquals(96, reply.body.size)
            // The replaced offer's token no longer resolves on the new server.
            assertEquals(404, get(second, "token-one").status)
        } finally {
            transport.stopOffer()
        }
    }

    @Test
    fun aStopForAReplacedGenerationIsIgnored() = runBlocking {
        val transport = KtorLanTransport(afterDownloadStopMs = 60_000, offerLifetimeMs = 60_000)
        try {
            transport.offer(ByteArray(32), "old", 1L)
            val current = transport.offer(ByteArray(32), "current", 2L)

            // A late stop aimed at the replaced offer must not take the
            // current one down with it.
            transport.stopOffer(generation = 1L)
            assertEquals(200, get(current, "current").status)
        } finally {
            transport.stopOffer()
        }
    }

    @Test
    fun downloadsAndProgressCarryTheirOfferingGeneration() = runBlocking {
        val transport = KtorLanTransport(afterDownloadStopMs = 60_000, offerLifetimeMs = 60_000)
        try {
            transport.offer(ByteArray(16), "first", 1L)
            val current = transport.offer(ByteArray(24), "second", 2L)

            val served = withTimeout(10_000) {
                val pending = async(Dispatchers.IO) { transport.servedOffers.first() }
                delay(200.milliseconds)
                get(current, "second")
                pending.await()
            }
            assertEquals(2L, served)

            val sample = withTimeout(10_000) {
                val pending = async(Dispatchers.IO) { transport.offerProgress.first() }
                delay(200.milliseconds)
                get(current, "second")
                pending.await()
            }
            assertEquals(2L, sample.generation)
            assertEquals(sample.progress.totalBytes, sample.progress.bytesSent)
        } finally {
            transport.stopOffer()
        }
    }

    @Test
    fun stoppingTwiceIsSafe() = runBlocking {
        val transport = KtorLanTransport(afterDownloadStopMs = 60_000, offerLifetimeMs = 60_000)
        transport.offer(ByteArray(8), "only", 1L)
        transport.stopOffer()
        transport.stopOffer()
        transport.stopOffer(generation = 1L)
    }
}
