package app.simt.echo.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** W5-01 gate: the LAN provider speaks the §7.2 shape and never leaks text. */
class OpenAiCompatVisionTest {

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): OpenAiCompatVision {
        val http = HttpClient(MockEngine) {
            engine { addHandler(handler) }
        }
        return OpenAiCompatVision(http, "http://192.168.1.10:8080/", "glimmer-local")
    }

    private val checkpoint = LocalCheckpoint(
        nodeId = "n1",
        keyframeId = "kf_n1_1",
        context = "TURN LEFT at 20 m (sensor-measured).",
        heroJpeg = byteArrayOf(1, 2, 3),
    )

    private fun reply(content: String): String =
        """{"choices":[{"message":{"role":"assistant","content":${jsonString(content)}}}]}"""

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private val answer = """{"nodeId":"n1","candidates":[
        {"title":"Red pillar","samPhrase":"red pillar","description":"A red pillar.",
         "box":[100,100,400,500],"distinctiveness":0.9,"permanence":1.0,"signText":null,
         "ambiguous":false,"privacyRisk":false,"cue":"Turn left at the red pillar"},
        {"title":"Dim wall","samPhrase":"grey wall","description":"A wall.",
         "box":[900,900,100,100],"distinctiveness":0.5,"permanence":0.5,"signText":null,
         "ambiguous":false,"privacyRisk":false,"cue":"Pass the wall"}]}"""

    @Test
    fun proposesCleanedCandidatesFromChatCompletion() = runTest {
        var path = ""
        var body = ""
        val vision = client({ request ->
            path = request.url.encodedPath
            body = request.body.toByteArray().decodeToString()
            respond(
                reply(answer),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })

        val candidates = vision.propose(checkpoint)

        assertEquals("/v1/chat/completions", path)
        assertTrue(body.contains("\"model\":\"glimmer-local\""), body.take(200))
        assertTrue(body.contains("image_url"), body.take(400))
        assertTrue(body.contains("json_schema"), body.take(400))
        assertEquals(2, candidates.size)
        // Top by d·p first, inverted box fixed, grid frame declared.
        assertEquals("Red pillar", candidates[0].title)
        assertEquals(listOf(100, 100, 400, 500), candidates[0].box)
        assertEquals(listOf(100, 100, 900, 900), candidates[1].box)
        assertEquals("AI_BOX_ONLY", candidates[0].source)
        assertEquals("glimmer-local (local)", candidates[0].models)
        assertEquals("kf_n1_1", candidates[0].keyframeId)
    }

    @Test
    fun proseWrappedJsonIsExtracted() = runTest {
        val vision = client({
            respond(
                reply("Here is the JSON:\n```json\n$answer\n```"),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertEquals(2, vision.propose(checkpoint).size)
    }

    @Test
    fun wrongCheckpointAndHttpFailureAreTyped() = runTest {
        val wrongNode = client({
            respond(
                reply("""{"nodeId":"n99","candidates":[]}"""),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        assertFailsWith<LocalModelException> { wrongNode.propose(checkpoint) }

        val failing = client({ respond("boom", HttpStatusCode.InternalServerError) })
        assertFailsWith<LocalModelException> { failing.propose(checkpoint) }

        val garbage = client({
            respond(reply("no json here"), HttpStatusCode.OK)
        })
        assertFailsWith<LocalModelException> { garbage.propose(checkpoint) }
    }
}
