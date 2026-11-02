package app.simt.echo.nav.ghost

import app.simt.echo.model.EmbeddedPrint
import kotlin.io.encoding.Base64
import kotlin.math.tanh

/**
 * Visual-fingerprint capture seam (plan W12-05).
 *
 * NO model binaries live here: there is no device, no weights in this
 * slice. What ships is the [VisualEmbedder] seam plus the §5 fallback as
 * the default implementation — dHash + 4×4 colour-moment at low filter
 * weight — so capture, storage ([EmbeddedPrint], int8[256]) and matching
 * are fully wired before any LiteRT embedder lands.
 *
 * Capture policy (§6 W12-05): 3 prints per checkpoint from the W11-02
 * approach strip (`[d − 8 m, d + 1 m]`, top by score) + waypoint prints
 * every 5 m ([WAYPOINT_STEP_M]). Prints land in [RoutePerception][app.simt.echo.model.RoutePerception]
 * only — inside the bundle body, under its encryption and expiry — and
 * never in any `transfer/` HTTP payload type (proven by grep).
 *
 * Embedder benchmark (recorded pair set: same spot from creator and
 * follower phones, different light; pick by precision ≥ 0.95 at τ and
 * ≤ 15 ms on the mid device):
 *
 * | candidate | size cap | precision @ τ | latency p50/p95 | verdict |
 * |---|---|---|---|---|
 * | MobileNetV3-small int8 (§5) | ≤ 4 MB | TBD | TBD | TBD |
 * | distilled DINOv2-small VPR head (§5) | ≤ 8 MB | TBD | TBD | TBD |
 * | dHash + 4×4 colour-moment (this file) | 0 (code only) | TBD | TBD | default until a row above picks |
 *
 * Procedure (exact, to run on the named mid device): 1. record the pair
 * set on two phones in two light conditions; 2. embed every frame with
 * each candidate; 3. sweep τ, report precision at the τ first reaching
 * ≥ 0.95; 4. time embed + persist per frame, report p50/p95 against the
 * 15 ms budget; 5. the winner replaces [FallbackEmbedder] behind this
 * same seam with its own `embedderId` + seed version (prints only ever
 * compare within one id + version — see [FingerprintMatch]).
 */
object FingerprintCapture {

    /** Projection-seed version stamped on every fallback print. */
    const val PROJECTION_SEED_VERSION = 1

    /** Waypoint prints: one per 5 m of route (W10-05 freeze). */
    const val WAYPOINT_STEP_M = 5.0

    /** Checkpoint window behind the node, metres (W11-02 strip). */
    const val STRIP_WINDOW_BEHIND_M = 8.0

    /** Checkpoint window past the node, metres (W11-02 strip). */
    const val STRIP_WINDOW_AHEAD_M = 1.0

    /**
     * Prints for one checkpoint at [nodeS]: strip frames in
     * `[nodeS − 8, nodeS + 1]` by score, top [EmbeddedPrint.MAX_PER_NODE]
     * embedded. Score ties break by odometer, then input order, so the
     * same strip always yields the same prints.
     */
    fun checkpointPrints(
        frames: List<PrintSourceFrame>,
        nodeS: Double,
        embedder: VisualEmbedder,
    ): List<EmbeddedPrint> {
        val lo = nodeS - STRIP_WINDOW_BEHIND_M
        val hi = nodeS + STRIP_WINDOW_AHEAD_M
        return frames
            .filter { it.odometerM in lo..hi }
            .sortedWith(
                compareByDescending<PrintSourceFrame> { it.score }
                    .thenBy { it.odometerM },
            )
            .take(EmbeddedPrint.MAX_PER_NODE)
            .map { embedder.embed(it.raw) }
    }

    /**
     * Waypoint distances over a route of [totalLengthM]: 0, 5, 10, … up
     * to and including the end. The caller embeds one frame per distance.
     */
    fun waypointDistances(totalLengthM: Double, stepM: Double = WAYPOINT_STEP_M): List<Double> {
        if (totalLengthM < 0.0 || stepM <= 0.0) return emptyList()
        return generateSequence(0.0) { it + stepM }
            .takeWhile { it <= totalLengthM + 1e-9 }
            .toList()
    }
}

/** One embeddable frame: structural hash + 4×4 colour-moment grid. */
data class FingerprintRaw(
    /** 64-bit dHash of the frame luma (see `FrameQuality.dHash`). */
    val dHash: Long,
    /** Mean luma per 4×4 cell, row-major, each in 0..1 (16 entries). */
    val moments: List<Double>,
)

/** One scored strip frame awaiting embedding. */
data class PrintSourceFrame(
    val raw: FingerprintRaw,
    val score: Double,
    val odometerM: Double,
)

/**
 * Image-embedder seam. Every implementation outputs int8[256] with its
 * own [embedderId]; prints only compare within one id + projection-seed
 * version (gated in [FingerprintMatch]), so swapping the LiteRT winner in
 * later never cross-matches stale prints.
 */
interface VisualEmbedder {
    val embedderId: String
    val projectionSeedVersion: Int
    fun embed(raw: FingerprintRaw): EmbeddedPrint
}

/**
 * §5 fallback embedder (the default until the benchmark picks): the 64
 * dHash bits (±1.0) plus the 16 colour moments form an 80-d descriptor,
 * pushed through a fixed seeded random projection to int8[256].
 *
 * Deterministic by construction: projection weights come from a
 * splitmix64 of ([PROJECTION_SEED], output index, input index) — no RNG
 * state, no stored matrix, same input bytes on any device.
 */
class FallbackEmbedder : VisualEmbedder {
    override val embedderId: String = FALLBACK_EMBEDDER_ID
    override val projectionSeedVersion: Int = FingerprintCapture.PROJECTION_SEED_VERSION

    override fun embed(raw: FingerprintRaw): EmbeddedPrint {
        require(raw.moments.size == COLOUR_CELLS) {
            "fallback embed: ${raw.moments.size} moments, need $COLOUR_CELLS"
        }
        val input = DoubleArray(INPUT_DIM) { j ->
            if (j < DHASH_BITS) {
                if ((raw.dHash ushr j) and 1L == 1L) 1.0 else -1.0
            } else {
                raw.moments[j - DHASH_BITS].coerceIn(0.0, 1.0) * 2.0 - 1.0
            }
        }
        val bytes = ByteArray(EmbeddedPrint.PRINT_BYTES) { i ->
            var acc = 0.0
            for (j in 0 until INPUT_DIM) acc += input[j] * projWeight(i * INPUT_DIM + j)
            (tanh(acc / PROJECTION_NORM) * 127.0).toInt().coerceIn(-127, 127).toByte()
        }
        return EmbeddedPrint(
            embedderId = embedderId,
            projectionSeedVersion = projectionSeedVersion,
            dataB64 = Base64.encode(bytes),
        )
    }

    private fun projWeight(index: Int): Double {
        var z = PROJECTION_SEED + index.toLong() * 0x9E3779B97F4A7C15UL.toLong()
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9UL.toLong()
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBUL.toLong()
        z = z xor (z ushr 31)
        return ((z ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
    }

    companion object {
        const val FALLBACK_EMBEDDER_ID = "echo-fallback-dhash-cm1"
        const val PROJECTION_SEED = 0xF16E5EEDL

        /** 64 dHash bits + 16 colour cells. */
        const val DHASH_BITS = 64
        const val COLOUR_CELLS = 16
        const val INPUT_DIM = DHASH_BITS + COLOUR_CELLS

        /** Normaliser into tanh: typical |dot| is a fraction of this. */
        const val PROJECTION_NORM = 8.0
    }
}
