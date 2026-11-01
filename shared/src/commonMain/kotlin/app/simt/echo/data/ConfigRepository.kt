package app.simt.echo.data

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

    fun get(): EchoConfig = docs.get(EchoConfig())

    fun set(config: EchoConfig) {
        docs.set(config)
    }

    fun update(transform: (EchoConfig) -> EchoConfig): EchoConfig =
        docs.update(EchoConfig(), transform)

    private companion object {
        const val CONFIG_PATH = "config/echo.json"
    }
}
