package app.simt.echo.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-phone provider factory (plan §7.5): a `.litertlm` model file
 * pushed into the app's models directory becomes a [LocalVision].
 * The engine is created lazily on first use (initialisation is heavy
 * and must not block startup), GPU first with a CPU fallback.
 */
actual fun createOnPhoneVision(modelPath: String): LocalVision? =
    LiteRtGemmaVision(modelPath)

/**
 * LiteRT-LM Gemma vision (W5-01). One conversation per checkpoint,
 * closed after use; the engine itself is kept for the process and
 * closed never (the OS reclaims it with the process, per LiteRT-LM
 * guidance for app-lifetime engines).
 */
private class LiteRtGemmaVision(private val modelPath: String) : LocalVision {

    override val modelId: String = "gemma-4-e2b (on phone)"

    private val lock = Any()
    private var engine: Engine? = null

    override suspend fun propose(checkpoint: LocalCheckpoint): List<AiCandidate> {
        val text = withContext(Dispatchers.Default) {
            try {
                val conversation = engine().createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(
                            "$LOCAL_SYSTEM_PROMPT Answer with JSON only.",
                        ),
                    ),
                )
                conversation.use { conv ->
                    val reply = conv.sendMessage(
                        Contents.of(
                            Content.ImageBytes(checkpoint.heroJpeg),
                            Content.Text(checkpoint.context),
                        ),
                    )
                    reply.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                }
            } catch (e: LocalModelException) {
                throw e
            } catch (e: Exception) {
                throw LocalModelException(
                    "On-phone model failed: ${e.message ?: "engine error"}",
                )
            }
        }
        val dto = parseLocalCheckpoint(text)
        if (dto.nodeId.isNotEmpty() && dto.nodeId != checkpoint.nodeId) {
            throw LocalModelException("On-phone model answered for a different checkpoint.")
        }
        return cleanLocalCandidates(checkpoint, modelId, dto.candidates)
    }

    /** Lazily initialised engine: GPU, falling back to CPU once. */
    private fun engine(): Engine = synchronized(lock) {
        engine?.let { return it }
        val cacheDir = File(modelPath).parent ?: "."
        val created = try {
            Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    cacheDir = cacheDir,
                ),
            ).also { it.initialize() }
        } catch (gpuFailure: Exception) {
            try {
                Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        cacheDir = cacheDir,
                    ),
                ).also { it.initialize() }
            } catch (cpuFailure: Exception) {
                throw LocalModelException(
                    "On-phone engine could not start: ${cpuFailure.message ?: gpuFailure.message ?: "unknown"}",
                )
            }
        }
        engine = created
        created
    }
}
