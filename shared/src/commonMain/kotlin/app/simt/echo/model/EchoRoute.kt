package app.simt.echo.model

import kotlinx.serialization.Serializable

/**
 * Frozen domain contract (implementation plan §5.2).
 *
 * This file is the most load-bearing contract in the plan: every lane
 * consumes these types. Changing a field here invalidates four lanes at
 * once — such a change needs a plan amendment, not a drive-by edit.
 * Geometry always originates from sensors; AI attaches to [nodes] and
 * never moves them.
 */
@Serializable
data class EchoRoute(
    val schemaVersion: Int = 1,
    val routeId: String,
    val name: String,
    val createdAtEpochMs: Long,
    val creatorLabel: String,
    val coordinateSystem: String = "relative_to_confirmed_start",
    val motionSource: String,
    val totalLengthM: Double,
    val start: StartAnchor,
    val nodes: List<RouteNode>,
    val legs: List<RouteLeg>,
    val path: List<PathPoint>,
    val landmarks: List<Landmark> = emptyList(),
    val privacy: PrivacyInfo = PrivacyInfo(),
    val ai: AiProvenance? = null,
    /**
     * Bundle v2 perception substrate (W10-05). Null for every v1 bundle;
     * additive with a default, so v1 JSON and v1 coders decode unchanged.
     * See [RoutePerception] for the frozen compatibility contract.
     */
    val perception: RoutePerception? = null,
)

@Serializable
data class StartAnchor(
    val description: String,
    val photoAssetId: String?,
    val alignmentHint: String,
)

@Serializable
data class RouteNode(
    val id: String,
    val type: NodeType,
    val atDistanceM: Double,
    val label: String,
    val turn: TurnDir? = null,
    val cue: String = "",
    val levelDelta: Int = 0,
    val landmarkIds: List<String> = emptyList(),
    val captureTMs: Long? = null,
)

@Serializable
enum class NodeType {
    START,
    TURN,
    CHECKPOINT,
    LEVEL_CHANGE,
    DESTINATION,
}

@Serializable
enum class TurnDir {
    LEFT,
    RIGHT,
    U_TURN,
}

@Serializable
data class RouteLeg(
    val fromNode: String,
    val toNode: String,
    val lengthM: Double,
    val headingRelDeg: Double,
    val levelDelta: Int = 0,
)

/** Path sampled every 0.5 m; x east-ish, y forward at start. */
@Serializable
data class PathPoint(
    val d: Double,
    val x: Double,
    val y: Double,
)

@Serializable
data class Landmark(
    val id: String,
    val nodeId: String,
    val title: String,
    val description: String,
    val cue: String,
    val samPhrase: String? = null,
    val signText: String? = null,
    val cropAssetId: String? = null,
    /** Faces, plates, screens: export preview defaults this crop OFF. */
    val privacyRisk: Boolean = false,
    val visibleFromM: Double? = null,
    val trackability: Double? = null,
    val samConfidence: Double? = null,
    val source: LandmarkSource,
    val models: String? = null,
    val confirmation: Confirmation,
    // Accepted-candidate geometry (plan amendment, review 0004 R3):
    // optional with null defaults, so bundles written before these
    // fields decode unchanged. They let Navigate render the SAM mask
    // over the crop (W5-02); without them the card shows the crop only.
    /** Candidate box in [frameW]×[frameH] frame pixels, when AI-sourced. */
    val box: List<Int>? = null,
    val frameW: Int? = null,
    val frameH: Int? = null,
    val maskSvgPath: String? = null,
    val maskW: Int? = null,
    val maskH: Int? = null,
    /** Keyframe the candidate was grounded on, when known. */
    val keyframeId: String? = null,
)

@Serializable
enum class LandmarkSource {
    AI_GROUNDED,
    AI_BOX_ONLY,
    MANUAL,
}

@Serializable
enum class Confirmation {
    CREATOR_VERIFIED,
    AI_SUGGESTED,
}

@Serializable
data class PrivacyInfo(
    val rawVideoIncluded: Boolean = false,
    val expiresAtEpochMs: Long? = null,
)

@Serializable
data class AiProvenance(
    val jobId: String,
    val scoutModel: String,
    val groundModel: String,
    val processedAtEpochMs: Long,
)

/** One bundled binary (image crop, start photo). Payload is base64. */
@Serializable
data class Asset(
    val id: String,
    val mime: String,
    val b64: String,
)

/** Bundle payload; [assets] are sorted by id before encoding (Wire §5.4). */
@Serializable
data class BundleBody(
    val route: EchoRoute,
    val assets: List<Asset>,
)
