package app.simt.echo.ai

/**
 * Android on-phone provider (LiteRT-LM). Replaced by the real engine
 * binding in the R4 step of review 0005; until then a model file is
 * discovered and reported, but proposing fails typed instead of
 * pretending to run.
 */
actual fun createOnPhoneVision(modelPath: String): LocalVision? =
    object : LocalVision {
        override val modelId: String = "gemma-4-e2b (on phone)"

        override suspend fun propose(checkpoint: LocalCheckpoint): List<AiCandidate> {
            throw LocalModelException("On-phone engine is not bundled in this build.")
        }
    }
