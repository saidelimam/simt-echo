package app.simt.echo.ai

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * LAN local model (plan §7.5): any OpenAI-compatible vision server
 * (llama-server, Ollama, …) at `{baseUrl}/v1/chat/completions`, same
 * messages and strict `response_format` as the cloud scout (§7.2).
 *
 * The LAN address is plain HTTP by design — it is the user's own
 * machine on their own network; the cloud client's HTTPS rule does
 * not apply here, and no token is sent. Parsing is defensive: the
 * first JSON object in the reply is schema-checked into candidates;
 * anything else is a typed [LocalModelException], never free text.
 */
@OptIn(ExperimentalEncodingApi::class)
class OpenAiCompatVision(
    private val http: HttpClient,
    private val baseUrl: String,
    override val modelId: String,
) : LocalVision {

    override suspend fun propose(checkpoint: LocalCheckpoint): List<AiCandidate> {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty() || modelId.isBlank()) {
            throw LocalModelException("Local model is not configured.")
        }
        val response = try {
            http.post("$url/v1/chat/completions") {
                contentType(ContentType.Application.Json)
                setBody(requestBody(checkpoint))
            }
        } catch (e: Exception) {
            throw LocalModelException("Local model unreachable: ${e.message ?: "network error"}")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            throw LocalModelException("Local model answered HTTP ${response.status.value}.")
        }
        val content = extractContent(text)
            ?: throw LocalModelException("Local model reply had no message content.")
        val dto = parseLocalCheckpoint(content)
        if (dto.nodeId.isNotEmpty() && dto.nodeId != checkpoint.nodeId) {
            throw LocalModelException("Local model answered for a different checkpoint.")
        }
        return cleanLocalCandidates(checkpoint, modelId, dto.candidates)
    }

    private fun requestBody(checkpoint: LocalCheckpoint): String {
        val body = buildJsonObject {
            put("model", modelId)
            put("temperature", 0.1)
            put("max_tokens", 1500)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", LOCAL_SYSTEM_PROMPT)
                }
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", checkpoint.context)
                        }
                        addJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put(
                                    "url",
                                    "data:image/jpeg;base64,${Base64.encode(checkpoint.heroJpeg)}",
                                )
                            }
                        }
                    }
                }
            }
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "echo_landmarks")
                    put("strict", true)
                    put("schema", CHECKPOINT_SCHEMA)
                }
            }
        }
        return body.toString()
    }

    private fun extractContent(responseText: String): String? = runCatching {
        val root = jsonParser.parseToJsonElement(responseText).jsonObject
        val choices = root["choices"]?.jsonArray ?: return null
        val message = choices.firstOrNull()?.jsonObject?.get("message")?.jsonObject ?: return null
        message["content"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        val jsonParser = Json { ignoreUnknownKeys = true }

        /** Strict single-checkpoint schema (worker SCHEMA, §7.1 form). */
        val CHECKPOINT_SCHEMA = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("nodeId") { put("type", "string") }
                putJsonObject("candidates") {
                    put("type", "array")
                    put("maxItems", 3)
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            for (field in listOf(
                                "title", "samPhrase", "description", "cue",
                            )) {
                                putJsonObject(field) { put("type", "string") }
                            }
                            putJsonObject("box") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "integer") }
                                put("minItems", 4)
                                put("maxItems", 4)
                            }
                            for (field in listOf("distinctiveness", "permanence")) {
                                putJsonObject(field) {
                                    put("type", "number")
                                    put("minimum", 0)
                                    put("maximum", 1)
                                }
                            }
                            putJsonObject("signText") {
                                putJsonArray("type") {
                                    add(kotlinx.serialization.json.JsonPrimitive("string"))
                                    add(kotlinx.serialization.json.JsonPrimitive("null"))
                                }
                            }
                            for (field in listOf("ambiguous", "privacyRisk")) {
                                putJsonObject(field) { put("type", "boolean") }
                            }
                        }
                        putJsonArray("required") {
                            for (field in listOf(
                                "title", "samPhrase", "description", "box", "distinctiveness",
                                "permanence", "signText", "ambiguous", "privacyRisk", "cue",
                            )) add(field)
                        }
                        put("additionalProperties", false)
                    }
                }
            }
            putJsonArray("required") {
                add("nodeId")
                add("candidates")
            }
            put("additionalProperties", false)
        }
    }
}
