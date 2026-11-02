package app.simt.echo.model

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64

/**
 * Bundle schema versions (plan W10-05, additive to the §5.2 freeze).
 *
 * V1 is every bundle written before perception existed. V2 writers stamp
 * [V2] when [EchoRoute.perception] is attached (see [withPerception]); the
 * container version in `BundleCodec` is untouched — perception rides inside
 * the body JSON, so no wire change was needed.
 */
object EchoSchemaVersion {
    const val V1 = 1
    const val V2 = 2
    const val CURRENT = V2

    /**
     * True when [route] was written by a newer app than this one understands.
     * The honest receiver path for that case is an "Update ECHO to open this
     * route" notice — never a crash, never silent mis-navigation.
     */
    fun needsUpdate(route: EchoRoute): Boolean = route.schemaVersion > CURRENT
}

/**
 * On-device perception substrate attached to a route (plan W10-05).
 *
 * Compatibility contract (frozen):
 * - Additive: `null` for every v1 bundle; v1 JSON decodes with perception
 *   `null` and navigates exactly as before.
 * - `EchoJson` keeps `ignoreUnknownKeys = true`, so a v1 app meeting a v2
 *   bundle drops `perception` (and any future keys) and still navigates the
 *   geometry: **ignore, never a crash** (W10-05 decisions 1+2, recorded).
 * - `schemaVersion > CURRENT` means a newer writer than this app: surface
 *   [EchoSchemaVersion.needsUpdate] as an honest "Update ECHO to open this
 *   route" notice instead of opening half-understood data.
 * - Size cap: the JSON encoding of a perception is at most [MAX_BYTES]
 *   (64 KB), enforced on the producer side by [validate], which
 *   `BundleCodec.encode` calls. Decode stays lenient — a received route is
 *   never refused for a shape the reader tolerates.
 *
 * Trust: fingerprints are image-derived tracking data. They travel inside
 * the bundle body, so they inherit the bundle's encryption (sealed
 * containers), expiry (`privacy.expiresAtEpochMs`), and export-preview
 * disclosure (`PERCEPTION_DISCLOSURE` / `ExportSummary.hasPerception`).
 *
 * Layout: `fingerprints` hold at most [EmbeddedPrint.MAX_PER_NODE] prints
 * per node; `waypointPrints` carry one print per 5 m of route; [MagProfile]
 * samples magnitude and vertical field every [MagProfile.MAG_STEP_M] metres
 * in µT×10 as int16; [sigilSeed] is reserved for the sigil accent
 * (consumed by a W13-03 follow-up; 0 keeps the routeId-derived accent);
 * [health] is the stored W12-07 score.
 */
@Serializable
data class RoutePerception(
    val fingerprints: List<NodeFingerprint> = emptyList(),
    val waypointPrints: List<WaypointPrint> = emptyList(),
    val magProfile: MagProfile? = null,
    /** Accent seed for the route sigil; 0 keeps the routeId-derived accent. */
    val sigilSeed: Long = 0L,
    val health: RouteHealth? = null,
    /** Additive v2.1 data; older readers ignore this key, not the route. */
    val parallax: ParallaxProfile? = null,
) {
    companion object {
        /** W10-05 size cap: the JSON encoding of a perception must fit. */
        const val MAX_BYTES = 64 * 1024
    }

    /**
     * Producer gate: throws [IllegalArgumentException] on the first shape or
     * size violation. Decoders never call this — receive stays tolerant.
     */
    fun validate(legCount: Int? = null): RoutePerception {
        fingerprints.forEach { it.validate() }
        waypointPrints.forEach {
            require(it.atDistanceM >= 0.0) {
                "waypoint print at ${it.atDistanceM} m: distance must be >= 0"
            }
            it.print.validate("waypoint@${it.atDistanceM}")
        }
        magProfile?.validate()
        health?.validate()
        parallax?.validate(legCount)
        val bytes = EchoJson.encodeToString(this).encodeToByteArray().size
        require(bytes <= MAX_BYTES) {
            "perception encodes to $bytes bytes, over the $MAX_BYTES-byte cap"
        }
        return this
    }
}

/** Fingerprints anchored to one route node ([RouteNode.id]). */
@Serializable
data class NodeFingerprint(
    val nodeId: String,
    val prints: List<EmbeddedPrint> = emptyList(),
) {
    fun validate(): NodeFingerprint {
        require(nodeId.isNotBlank()) { "node fingerprint: nodeId is blank" }
        require(prints.size <= EmbeddedPrint.MAX_PER_NODE) {
            "node $nodeId: ${prints.size} prints, at most ${EmbeddedPrint.MAX_PER_NODE}"
        }
        prints.forEach { it.validate("node $nodeId") }
        return this
    }
}

/** One embedding vector with the identity of the model that made it. */
@Serializable
data class EmbeddedPrint(
    val embedderId: String,
    /** Projection-seed version: prints only compare within one version. */
    val projectionSeedVersion: Int,
    /** Base64 of the int8[256] embedding vector (256 bytes decoded). */
    val dataB64: String,
) {
    companion object {
        const val PRINT_BYTES = 256
        const val MAX_PER_NODE = 3
    }

    fun validate(ref: String): EmbeddedPrint {
        require(embedderId.isNotBlank()) { "fingerprint ($ref): embedderId is blank" }
        require(projectionSeedVersion >= 1) {
            "fingerprint ($ref): projectionSeedVersion ${projectionSeedVersion} < 1"
        }
        val bytes = try {
            Base64.decode(dataB64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("fingerprint ($ref): dataB64 is not valid base64", e)
        }
        require(bytes.size == PRINT_BYTES) {
            "fingerprint ($ref): decoded to ${bytes.size} bytes, need $PRINT_BYTES"
        }
        return this
    }
}

/** Spaced print along the walk; producers emit one per 5 m (W10-05). */
@Serializable
data class WaypointPrint(
    val atDistanceM: Double,
    val print: EmbeddedPrint,
)

/** Magnetic profile along the walk, sampled every [MAG_STEP_M] metres. */
@Serializable
data class MagProfile(
    val stepM: Double = MAG_STEP_M,
    /** Total-field magnitude in µT×10, one int16 per step. */
    val magnitude: List<Short> = emptyList(),
    /** Vertical component in µT×10, one int16 per step. */
    val vertical: List<Short> = emptyList(),
) {
    companion object {
        const val MAG_STEP_M = 0.5
    }

    fun validate(): MagProfile {
        require(stepM == MAG_STEP_M) {
            "magProfile: stepM $stepM m, the freeze fixes $MAG_STEP_M m"
        }
        require(magnitude.size == vertical.size) {
            "magProfile: ${magnitude.size} magnitude vs ${vertical.size} vertical samples"
        }
        return this
    }
}

/** Stored route-health score (plan W12-07 writes this on save). */
@Serializable
data class RouteHealth(
    /** 0..1, higher is healthier. */
    val score01: Double,
    val checkedAtEpochMs: Long,
    val notes: List<String> = emptyList(),
) {
    fun validate(): RouteHealth {
        require(score01 in 0.0..1.0) { "health: score $score01 outside 0..1" }
        require(checkedAtEpochMs >= 0) { "health: checkedAtEpochMs $checkedAtEpochMs < 0" }
        return this
    }
}

/**
 * Attaches validated v2 perception and stamps [EchoSchemaVersion.V2].
 * Throws [IllegalArgumentException] when [RoutePerception.validate] fails,
 * so an invalid perception can never be encoded.
 */
fun EchoRoute.withPerception(perception: RoutePerception): EchoRoute {
    perception.validate(legs.size)
    return copy(schemaVersion = EchoSchemaVersion.V2, perception = perception)
}
