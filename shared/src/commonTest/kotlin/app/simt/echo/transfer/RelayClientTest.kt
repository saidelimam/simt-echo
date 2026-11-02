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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
