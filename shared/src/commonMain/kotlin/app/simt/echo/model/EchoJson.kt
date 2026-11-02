package app.simt.echo.model

import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for every on-disk and on-wire payload
 * (plan §5.2). Unknown keys are tolerated so newer bundles still import;
 * defaults are encoded so older readers see the same shape.
 *
 * W10-05 decision (recorded, frozen): unknown-key tolerance stays ON
 * (`ignoreUnknownKeys = true`). That is what makes a v1 app meeting a v2
 * bundle ignore `perception` — and any future additive keys — and still
 * navigate the geometry: ignore, never a crash. A route whose schemaVersion
 * exceeds [EchoSchemaVersion.CURRENT] is the one case that needs an app
 * update ("Update ECHO to open this route"); see
 * [EchoSchemaVersion.needsUpdate].
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
