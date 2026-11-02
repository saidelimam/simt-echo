package app.simt.echo.transfer

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineStart
import app.simt.echo.platform.TransferProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * W9-03 gate: the wired [KtorRelayClient] speaks the frozen W4-05 relay
 * contract — raw sealed bytes up, `{code}` back; raw bytes down by code —
 * behind the same Bearer surface as [EchoApiClient].
 */
class RelayClientTest {

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        baseUrl: String = "https://echo.test",
        token: String = "tok",
    ) = HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
    }.let { KtorRelayClient(it, baseUrl, token) }

    @Test
    fun uploadPostsCiphertextAndReturnsCode() = runTest {
        var method: HttpMethod? = null
        var path = ""
        var auth = ""
        var sent = byteArrayOf()
        val sealed = byteArrayOf(69, 67, 88, 49, 1, 2, 3)
        val api = client({ request ->
            method = request.method
            path = request.url.encodedPath
            auth = request.headers[HttpHeaders.Authorization] ?: ""
            sent = request.body.toByteArray()
            respond(
                """{"ok":true,"code":"ABC234","expiresAt":123}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertEquals("ABC234", api.upload(sealed))
        assertEquals(HttpMethod.Post, method)
        assertEquals("/v1/relay", path)
        assertEquals("Bearer tok", auth)
        assertContentEquals(sealed, sent)
    }

    @Test
    fun uploadFailureThrows() = runTest {
        val api = client({ _ ->
            respond(
                """{"ok":false,"error":"unauthorized"}""",
                HttpStatusCode.Unauthorized,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertFailsWith<IllegalStateException> { api.upload(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun downloadGetsOpaqueBytesByCode() = runTest {
        var path = ""
        var auth = ""
        val stored = byteArrayOf(69, 67, 88, 49, 9, 8, 7)
        val api = client({ request ->
            path = request.url.encodedPath
            auth = request.headers[HttpHeaders.Authorization] ?: ""
            assertEquals(HttpMethod.Get, request.method)
            respond(stored, HttpStatusCode.OK)
        })
        assertContentEquals(stored, api.download("ABC234"))
        assertEquals("/v1/relay/ABC234", path)
        assertEquals("Bearer tok", auth)
    }

    @Test
    fun downloadMissingCodeThrows() = runTest {
        val api = client({ _ ->
            respond(
                """{"ok":false,"error":"not_found"}""",
                HttpStatusCode.NotFound,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertFailsWith<IllegalStateException> { api.download("ZZZZZZ") }
    }

    @Test
    fun uploadEmitsProgressEndingAtTotal() = runTest {
        val sealed = byteArrayOf(69, 67, 88, 49, 1, 2, 3)
        val api = client({ _ ->
            respond(
                """{"ok":true,"code":"ABC234","expiresAt":123}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        // Undispatched: subscribed before the upload can emit anything.
        val done = async(start = CoroutineStart.UNDISPATCHED) {
            api.progress.filter { it.isComplete }.first()
        }
        api.upload(sealed)
        val progress = done.await()
        assertEquals(sealed.size.toLong(), progress.totalBytes)
        assertEquals(sealed.size.toLong(), progress.bytesSent)
    }

    @Test
    fun downloadEmitsProgressEndingAtTotal() = runTest {
        val stored = byteArrayOf(69, 67, 88, 49, 9, 8, 7)
        val api = client({ _ ->
            respond(stored, HttpStatusCode.OK)
        })
        val done = async(start = CoroutineStart.UNDISPATCHED) {
            api.progress.filter { it.isComplete }.first()
        }
        assertContentEquals(stored, api.download("ABC234"))
        val progress = done.await()
        assertEquals(stored.size.toLong(), progress.totalBytes)
        assertEquals(stored.size.toLong(), progress.bytesSent)
    }

    @Test
    fun downloadWithoutContentLengthStaysIndeterminateUntilTheEnd() = runTest {
        val body = ByteArray(HTTP_PROGRESS_CHUNK_BYTES * 3 + 5) { 1 }
        val api = client({ _ -> respond(body, HttpStatusCode.OK) })
        val seen = mutableListOf<TransferProgress>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { api.progress.collect { seen += it } }

        assertEquals(body.size, api.download("ABC234").size)
        advanceUntilIdle()
        collector.cancel()

        assertTrue(seen.size >= 2, "chunks plus the final sample: $seen")
        // Unknown total: nothing may read as finished or part-way mid-stream.
        for (midStream in seen.dropLast(1)) {
            assertFalse(midStream.isComplete, "mid-stream sample must not be complete: $midStream")
            assertEquals(0f, midStream.fraction, "mid-stream sample must be indeterminate: $midStream")
        }
        assertEquals(TransferProgress(body.size.toLong(), body.size.toLong()), seen.last())
    }

    @Test
    fun downloadWithContentLengthReportsTheDeclaredTotalMidStream() = runTest {
        val body = ByteArray(HTTP_PROGRESS_CHUNK_BYTES * 3) { 1 }
        val api = client({ _ ->
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        })
        val seen = mutableListOf<TransferProgress>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { api.progress.collect { seen += it } }

        api.download("ABC234")
        advanceUntilIdle()
        collector.cancel()

        val first = seen.first()
        assertEquals(body.size.toLong(), first.totalBytes)
        assertFalse(first.isComplete)
        assertTrue(first.fraction in 0.01f..0.99f, "declared total gives a real fraction: $first")
    }

    @Test
    fun aCollectorThatFallsBehindStillSeesTheFinalSample() = runTest {
        // More chunks than the progress buffer holds, delivered before the
        // collector gets to run: the final (total, total) must survive.
        val body = ByteArray(HTTP_PROGRESS_CHUNK_BYTES * 70) { 1 }
        val api = client({ _ -> respond(body, HttpStatusCode.OK) })
        val final = async(start = CoroutineStart.UNDISPATCHED) {
            api.progress.filter { it.isComplete }.first()
        }

        api.download("ABC234")
        runCurrent()
        advanceUntilIdle()

        assertTrue(final.isCompleted, "the final sample was dropped by a full buffer")
        assertEquals(body.size.toLong(), final.await().totalBytes)
    }
}
