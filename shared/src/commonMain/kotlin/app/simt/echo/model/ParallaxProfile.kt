package app.simt.echo.model

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.math.PI

/**
 * v2.1 wire freeze. Blobs are base64, not JSON number arrays (64 KB shared cap).
 * Bucket i covers [i * stepM, (i + 1) * stepM) in the creator's PDR metres.
 *
 * inverseDepth: signed bytes; 0 is unknown. For nonzero code c, q is c
 * when c < 0, otherwise c - 1; rho = 2^(q/16) per creator metre. This
 * reserves zero without losing rho = 1. Trackability is unsigned 0..255.
 * Missing leg uncertainty is an empty list; otherwise exactly one per leg.
 *
 * Strips are lossy 1-D marginals, not uniquely reconstructable source images.
 * They still reveal place similarity: encryption/expiry/disclosure apply.
 * See docs/research/parallax-contract.md for the argument and its limits.
 */
@Serializable
data class ParallaxProfile(
    val parallaxVersion: Int = VERSION,
    val stepM: Double = STEP_M,
    val inverseDepth: String = "",
    val trackability: String = "",
    val viewStrips: List<ViewStrip> = emptyList(),
    val legSigmaRad: List<Double> = emptyList(),
    val estimatorId: String,
    /** Recorded CameraIntrinsics source/class; never assumed to be calibrated. */
    val intrinsicsClass: String,
) {
    /** Only like estimators cancel bias. Future versions are ignored, not guessed. */
    fun compatibleWith(liveEstimatorId: String): Boolean =
        parallaxVersion == VERSION && stepM == STEP_M &&
            liveEstimatorId.isNotBlank() && estimatorId == liveEstimatorId

    fun validate(legCount: Int? = null): ParallaxProfile {
        require(parallaxVersion == VERSION) { "parallax: unsupported producer version $parallaxVersion" }
        require(stepM == STEP_M) { "parallax: stepM must be $STEP_M" }
        require(estimatorId.isNotBlank()) { "parallax: estimatorId is blank" }
        require(intrinsicsClass.isNotBlank()) { "parallax: intrinsicsClass is blank" }
        val depth = Base64.decode(inverseDepth)
        val quality = Base64.decode(trackability)
        require(depth.size == quality.size) { "parallax: depth and trackability must pair" }
        require(legSigmaRad.all { it.isFinite() && it in 0.0..PI }) { "parallax: invalid leg sigma" }
        require(legCount == null || legSigmaRad.isEmpty() || legSigmaRad.size == legCount) {
            "parallax: sigma count must equal route leg count"
        }
        var previousDistance = -1.0
        viewStrips.forEach {
            it.validate()
            require(it.atDistanceM > previousDistance) { "parallax: strips must have increasing distances" }
            previousDistance = it.atDistanceM
        }
        return this
    }

    companion object {
        const val VERSION = 1
        const val STEP_M = 0.5
    }
}

/** 64 normalised int8 column values followed by 32 normalised int8 row values. */
@Serializable
data class ViewStrip(val atDistanceM: Double, val dataB64: String) {
    fun validate(): ViewStrip {
        require(atDistanceM.isFinite() && atDistanceM >= 0.0) { "view strip: invalid distance" }
        require(Base64.decode(dataB64).size == BYTES) { "view strip: need $BYTES bytes" }
        return this
    }

    companion object {
        const val COLUMNS = 64
        const val ROWS = 32
        const val BYTES = COLUMNS + ROWS
    }
}
