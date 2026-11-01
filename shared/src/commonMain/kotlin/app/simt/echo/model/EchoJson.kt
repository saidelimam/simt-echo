package app.simt.echo.model

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for every on-disk and on-wire payload
 * (plan §5.2). Unknown keys are tolerated so newer bundles still import;
 * defaults are encoded so older readers see the same shape.
 */
val EchoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** Id conventions (plan §5.2): the only place these formats are built. */
object EchoIds {
    fun nodeId(index: Int): String = "n$index"
    fun landmarkId(nodeId: String, index: Int): String = "lm_${nodeId}_$index"
    fun assetId(name: String): String = "a_$name"
}
