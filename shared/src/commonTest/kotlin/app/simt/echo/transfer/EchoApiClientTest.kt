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
import kotlinx.coroutines.flow.toList
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
    fun cleartextBaseUrlIsRefusedExceptOnLoopback() = runTest {
        val cleartext = client({ respond("{}") }, baseUrl = "http://echo.test")
        assertFailsWith<EchoApiError.NotConfigured> { cleartext.submit("{}", emptyList()) }
        assertFailsWith<EchoApiError.NotConfigured> { cleartext.get("job-1") }
        assertFailsWith<EchoApiError.NotConfigured> { cleartext.delete("job-1") }

        val loopback = client({
            respond("""{"ok":true,"jobId":"j"}""", HttpStatusCode.Accepted)
        }, baseUrl = "http://127.0.0.1:8787")
        assertEquals("j", loopback.submit("{}", emptyList()))
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

    // ── W11-08: hash-first upload, job create, event stream ────────────

    @Test
    fun uploadTimeoutIsSizedToTheFile() {
        // Floor 30 s, then +1 s per 100 KB chunk (ceil).
        assertEquals(30_000L, uploadTimeoutMs(0L))
        assertEquals(31_000L, uploadTimeoutMs(1L))
        assertEquals(31_000L, uploadTimeoutMs(102_400L))
        assertEquals(32_000L, uploadTimeoutMs(102_401L))
        assertEquals(30_000L + 103_000L, uploadTimeoutMs(10L * 1024L * 1024L))
    }

    @Test
    fun createUploadPostsHashesAndReturnsMissing() = runTest {
        var path = ""
        var body = ""
        val api = client({ request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            assertEquals(HttpMethod.Post, request.method)
            respond(
                """{"ok":true,"uploadId":"up-1","missing":["clip_n1"]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val created = api.createUpload(
            listOf(
                UploadFileRef("kf_n1_0", "a".repeat(64), 512L),
                UploadFileRef("clip_n1", "b".repeat(64), 1_000_000L),
            ),
        )
        assertEquals("up-1", created.uploadId)
        assertEquals(listOf("clip_n1"), created.missing)
        assertEquals("/v1/uploads", path)
        assertTrue(body.contains(""""sha256":"${"a".repeat(64)}""""), body)
    }

    @Test
    fun uploadStatusListsMissing() = runTest {
        var path = ""
        val api = client({ request ->
            path = request.url.encodedPath
            respond("""{"ok":true,"uploadId":"up-1","missing":[]}""", HttpStatusCode.OK)
        })
        assertEquals(emptyList<String>(), api.uploadMissing("up-1"))
        assertEquals("/v1/uploads/up-1", path)
    }

    @Test
    fun putSendsBytesAndMapsShaMismatchToStale() = runTest {
        var path = ""
        var method: HttpMethod? = null
        var bytes = byteArrayOf()
        val api = client({ request ->
            path = request.url.encodedPath
            method = request.method
            bytes = request.body.toByteArray()
            respond("""{"ok":true,"name":"kf_n1_0"}""", HttpStatusCode.OK)
        })
        api.putUploadFile("up-1", UploadFile("kf_n1_0", "image/jpeg", byteArrayOf(1, 2, 3)))
        assertEquals("/v1/uploads/up-1/kf_n1_0", path)
        assertEquals(HttpMethod.Put, method)
        assertTrue(bytes.contentEquals(byteArrayOf(1, 2, 3)))

        val stale = client({
            respond("""{"ok":false,"error":"sha_mismatch"}""", HttpStatusCode.BadRequest)
        })
        assertFailsWith<EchoApiError.UploadStale> {
            stale.putUploadFile("up-1", UploadFile("kf_n1_0", "image/jpeg", byteArrayOf(9)))
        }
    }

    @Test
    fun createJobSendsIdempotencyKey() = runTest {
        var body = ""
        val api = client({ request ->
            body = request.body.toByteArray().decodeToString()
            respond("""{"ok":true,"jobId":"job-7"}""", HttpStatusCode.Accepted)
        })
        val jobId = api.createJob("""{"schema":1}""", "up-1", "idem-key-1")
        assertEquals("job-7", jobId)
        assertTrue(body.contains(""""uploadId":"up-1""""), body)
        assertTrue(body.contains(""""idempotencyKey":"idem-key-1""""), body)
        assertTrue(body.contains(""""manifest":{"schema":1}"""), body)
    }

    @Test
    fun createJobFromStagedSendsUploadIds() = runTest {
        var path = ""
        var body = ""
        val api = client({ request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond("""{"ok":true,"jobId":"job-9"}""", HttpStatusCode.Accepted)
        })
        val jobId = api.createJobFromStaged("""{"schema":1}""", listOf("up-1", "up-2"), "idem-walk-1")
        assertEquals("job-9", jobId)
        assertEquals("/v1/jobs", path)
        assertTrue(body.contains(""""uploadIds":["up-1","up-2"]"""), body)
        assertTrue(body.contains(""""idempotencyKey":"idem-walk-1""""), body)
    }

    @Test
    fun createJobFromStagedRefusesEmptySessions() = runTest {
        val api = client({ respond("""{"ok":true,"jobId":"job-9"}""", HttpStatusCode.Accepted) })
        assertFailsWith<IllegalArgumentException> {
            api.createJobFromStaged("""{"schema":1}""", emptyList(), "idem-walk-1")
        }
    }

    @Test
    fun deleteUploadTreatsGoneAsGone() = runTest {
        var path = ""
        var method: HttpMethod? = null
        val api = client({ request ->
            path = request.url.encodedPath
            method = request.method
            respond("", HttpStatusCode.NoContent)
        })
        api.deleteUpload("up-1")
        assertEquals("/v1/uploads/up-1", path)
        assertEquals(HttpMethod.Delete, method)
        val gone = client({ respond("", HttpStatusCode.NotFound) })
        gone.deleteUpload("up-1")
        val broken = client({ respond("boom", HttpStatusCode.InternalServerError) })
        assertFailsWith<EchoApiError.Server> { broken.deleteUpload("up-1") }
    }

    @Test
    fun watchEventsParsesFramesAndSendsLastEventId() = runTest {
        val seenIds = mutableListOf<String?>()
        val api = client({ request ->
            seenIds += request.headers["Last-Event-ID"]
            assertEquals("/v1/jobs/job-1/events", request.url.encodedPath)
            assertEquals("text/event-stream", request.headers[HttpHeaders.Accept])
            respond(
                "id: evt-1\ndata: {\"id\":\"evt-1\",\"type\":\"stage\",\"stage\":\"scouting\"}\n\n" +
                    "id: evt-2\ndata: {\"id\":\"evt-2\",\"type\":\"checkpoint\"," +
                    "\"nodeId\":\"n1\",\"status\":\"grounded\",\"candidates\":[]}\n\n" +
                    "data: [DONE]\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        })
        val events = api.watchEvents("job-1", "evt-0").toList()
        assertEquals(listOf<String?>("evt-0"), seenIds)
        assertEquals(2, events.size)
        assertEquals(JobEvent.Stage("evt-1", JobStage.SCOUTING), events[0])
        val checkpoint = events[1] as JobEvent.Checkpoint
        assertEquals("n1", checkpoint.nodeId)
        assertEquals("[]", checkpoint.candidatesRaw)
    }

    @Test
    fun parseSseSkipsDoneAndMalformedFrames() {
        val events = parseSseEvents(
            "data: [DONE]\n\n" +
                "data: not json\n\n" +
                "id: e1\ndata: {\"id\":\"e1\",\"type\":\"complete\",\"result\":{\"jobId\":\"j\"}}\n\n",
        )
        assertEquals(1, events.size)
        val complete = events.single() as JobEvent.Complete
        assertEquals("e1", complete.id)
        assertTrue(complete.resultRaw!!.contains("jobId"), complete.resultRaw!!)
    }
}
