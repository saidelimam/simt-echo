package app.simt.echo.data

import app.simt.echo.capture.STEP_K_DEFAULT
import app.simt.echo.capture.STEP_K_MAX
import app.simt.echo.capture.STEP_K_MIN
import app.simt.echo.capture.isPlausibleStepK
import app.simt.echo.model.EchoConfig
import app.simt.echo.model.EchoJson
import kotlinx.serialization.encodeToString

/**
 * Generic persisted JSON document (slice W4-02).
 *
 * One reader/writer for every small settings document in the app
 * (`EchoConfig` first, trial defaults later): the document type only
 * supplies its path plus encode/decode lambdas, and this class owns the
 * read-modify-write. Missing or corrupt files degrade to the caller's
 * default — a bad settings write can never brick capture.
 */
class JsonDocStore<T>(
    private val store: FileStore,
    private val path: String,
    private val decode: (String) -> T,
    private val encode: (T) -> String,
) {
    fun get(default: T): T {
        val text = store.readText(path) ?: return default
        return runCatching { decode(text) }.getOrDefault(default)
    }

    fun set(value: T) {
        store.writeText(path, encode(value))
    }

    /** Read, transform, write back; returns the stored result. */
    fun update(default: T, transform: (T) -> T): T {
        val next = transform(get(default))
        set(next)
        return next
    }
}

/**
 * Tunable thresholds (plan §5: owned by W4-02 after W2-06).
 * Persisted as `config/echo.json`; every algorithm reads config and never
 * hard-codes past it.
 */
class ConfigRepository(store: FileStore) {
    private val docs = JsonDocStore(
        store = store,
        path = CONFIG_PATH,
        decode = { EchoJson.decodeFromString<EchoConfig>(it) },
        encode = { EchoJson.encodeToString(it) },
    )

    /** Persisted config, sanitised: an absurd stored stepK (hand-edited
     * or written by a bad calibration) falls back to the default rather
     * than silently saturating the PDR clamp on every walk. The learned
     * stride (W12-03) is sanitised the same way: an implausible stored K
     * degrades to null, a negative leg count to zero. */
    fun get(): EchoConfig {
        val stored = docs.get(EchoConfig())
        val stepK = if (isPlausibleStepK(stored.stepK)) stored.stepK else STEP_K_DEFAULT
        val learned =
            if (stored.learnedStepK != null && isPlausibleStepK(stored.learnedStepK)) {
                stored.learnedStepK
            } else {
                null
            }
        val legs = stored.learnedStrideLegs.coerceAtLeast(0)
        return if (stepK == stored.stepK && learned == stored.learnedStepK && legs == stored.learnedStrideLegs) {
            stored
        } else {
            stored.copy(stepK = stepK, learnedStepK = learned, learnedStrideLegs = legs)
        }
    }

    fun set(config: EchoConfig) {
        docs.set(config)
    }

    /**
     * Records one stride-learning posterior (W12-03): K clamped into the
     * plausible band, leg count floored at zero. Additive — every other
     * key round-trips untouched.
     */
    fun setLearnedStride(k: Double, legs: Int): EchoConfig =
        update {
            it.copy(
                learnedStepK = k.coerceIn(STEP_K_MIN, STEP_K_MAX),
                learnedStrideLegs = legs.coerceAtLeast(0),
            )
        }

    fun update(transform: (EchoConfig) -> EchoConfig): EchoConfig =
        docs.update(EchoConfig(), transform)

    private companion object {
        const val CONFIG_PATH = "config/echo.json"
    }
}
