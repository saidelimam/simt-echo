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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W3-02 gate: submit/get/delete against the frozen worker contract. */
class EchoApiClientTest {

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
        baseUrl: String = "https://echo.test",
        token: String = "tok",
    ) = HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
    }.let { EchoApiClient(it, baseUrl, token) }

    @Test
    fun submitPostsMultipartAndReturnsJobId() = runTest {
        var path = ""
        var auth = ""
        var contentType: String? = null
        val api = client({ request ->
            path = request.url.encodedPath
            auth = request.headers[HttpHeaders.Authorization] ?: ""
            contentType = request.body.contentType?.toString()
            assertEquals(HttpMethod.Post, request.method)
            respond(
                """{"ok":true,"jobId":"job-1"}""",
                HttpStatusCode.Accepted,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val jobId = api.submit(
            """{"schema":1}""",
            listOf(UploadFile("kf_n1_0", "image/jpeg", byteArrayOf(1, 2, 3))),
        )
        assertEquals("job-1", jobId)
        assertEquals("/v1/jobs", path)
        assertEquals("Bearer tok", auth)
        assertTrue(contentType?.startsWith("multipart/form-data") == true, contentType.toString())
    }

    @Test
    fun submitSendsManifestAndFileFields() = runTest {
        var text = ""
        val api = client({ request ->
            text = request.body.toByteArray().decodeToString()
            respond("""{"ok":true,"jobId":"j"}""", HttpStatusCode.Accepted)
        })
        api.submit(
            """{"schema":1,"draftId":"d1"}""",
            listOf(
                UploadFile("kf_n1_0", "image/jpeg", byteArrayOf(9)),
                UploadFile("clip_n1", "video/mp4", byteArrayOf(7, 7)),
            ),
        )
        assertTrue(text.contains(""""schema":1"""), text.take(300))
        assertTrue(text.contains("filename=\"kf_n1_0\""), text.take(600))
        assertTrue(text.contains("filename=\"clip_n1\""), text.take(1200))
        assertTrue(text.contains("video/mp4"), text.take(1200))
    }

    @Test
    fun submitMapsConsentRequired() = runTest {
        val api = client({
            respond(
                """{"ok":false,"error":"consent_required"}""",
                HttpStatusCode.BadRequest,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertFailsWith<EchoApiError.ConsentRequired> {
            api.submit("""{"schema":1}""", emptyList())
        }
    }

    @Test
    fun submitMapsAuthAndRateLimit() = runTest {
        val denied = client({
            respond("""{"ok":false,"error":"unauthorized"}""", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<EchoApiError.Unauthorized> { denied.submit("{}", emptyList()) }
        val limited = client({
            respond("""{"ok":false,"error":"rate_limited"}""", HttpStatusCode.TooManyRequests)
        })
        assertFailsWith<EchoApiError.RateLimited> { limited.submit("{}", emptyList()) }
    }

    @Test
    fun submitFailsClosedWithoutConfig() = runTest {
        val api = client({ respond("{}") }, baseUrl = "", token = "")
        assertFailsWith<EchoApiError.NotConfigured> { api.submit("{}", emptyList()) }
    }

    @Test
    fun getParsesRunningAndComplete() = runTest {
        val running = client({
            respond(
                """{"ok":true,"status":"running","stage":"scouting"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val poll = running.get("job-1")
        assertEquals("running", poll.status)
        assertEquals(JobStage.SCOUTING, poll.stage)

        val done = client({
            respond(
                """{"ok":true,"status":"complete","stage":"complete","result":{"a":1}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val finished = done.get("job-1")
        assertEquals(JobStage.COMPLETE, finished.stage)
        assertTrue(finished.resultRaw!!.contains("\"a\":1"), finished.resultRaw!!)
    }

    @Test
    fun getMapsNotFound() = runTest {
        val api = client({ respond("{}", HttpStatusCode.NotFound) })
        assertFailsWith<EchoApiError.JobNotFound> { api.get("gone") }
    }

    @Test
    fun deleteTreatsGoneAsGone() = runTest {
        val gone = client({ respond("", HttpStatusCode.NotFound) })
        gone.delete("job-1")
        val broken = client({ respond("boom", HttpStatusCode.InternalServerError) })
        assertFailsWith<EchoApiError.Server> { broken.delete("job-1") }
    }

    @Test
    fun samEnabledReadsHealthFlag() = runTest {
        val on = client({
            respond("""{"ok":true,"version":"0.1.0","metaKeyConfigured":true}""", HttpStatusCode.OK)
        })
        assertTrue(on.samEnabled())
        val off = client({
            respond("""{"ok":true,"version":"0.1.0","metaKeyConfigured":false}""", HttpStatusCode.OK)
        })
        assertTrue(!off.samEnabled())
    }
}
