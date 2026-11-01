package app.simt.echo.ai

/**
 * Provider routing for Review (plan §7 routing / W5-01).
 *
 * The cloud result is the default. A local model is used **only if
 * one is configured** and (the worker produced nothing usable or
 * "Prefer local model" is on) — otherwise the creator picks landmarks
 * manually, exactly as before. Every path emits [AiCandidate]s, so
 * `CandidateMerger` and Review never branch on provider; the result's
 * `models` footnote names what actually ran.
 */
class AiRouter(private val local: LocalVision?) {

    /** True when a local provider is configured and may be called. */
    val localAvailable: Boolean get() = local != null

    /** The configured local provider, or null. */
    fun localOrNull(): LocalVision? = local

    /**
     * Runs the local provider over [checkpoints] and assembles the
     * worker-shaped result (jobId `local`, scout footnote
     * `"<modelId> (local)"`, no grounding). Per-checkpoint failures are
     * collected: one bad checkpoint yields no candidates for it, but a
     * provider that fails everywhere surfaces its typed error so the
     * UI can say why local produced nothing.
     */
    suspend fun proposeLocal(checkpoints: List<LocalCheckpoint>): AiJobResult {
        val provider = local ?: throw LocalModelException("No local model configured.")
        val results = mutableListOf<AiCheckpoint>()
        var lastError: LocalModelException? = null
        var successes = 0
        for (checkpoint in checkpoints) {
            try {
                val candidates = provider.propose(checkpoint)
                successes++
                if (candidates.isNotEmpty()) {
                    results += AiCheckpoint(nodeId = checkpoint.nodeId, candidates = candidates)
                }
            } catch (e: LocalModelException) {
                lastError = e
            }
        }
        if (successes == 0 && lastError != null) throw lastError
        return AiJobResult(
            jobId = "local",
            models = AiModels(scout = "${provider.modelId} (local)", ground = "none"),
            checkpoints = results,
        )
    }
}
