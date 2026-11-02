package app.simt.echo.capture

import kotlin.math.abs
import kotlin.math.min

/**
 * Pure on-device frame-quality scoring (plan W11-01).
 *
 * Every function here is a pure function of its inputs: no coroutines, no
 * platform imports, no allocation on the scoring path (locals and
 * primitives only — the "allocation-free hot path" is by construction, not
 * by discipline). All scoring runs on the decimated luma plane carried by
 * [FrameSample]; callers pass the plane dimensions
 * (`width / decimation`, `height / decimation`).
 */
object FrameQuality {

    /** Luma values below this are crushed blacks for exposure purposes. */
    const val EXPOSURE_LO = 16

    /** Luma values above this are blown highlights for exposure purposes. */
    const val EXPOSURE_HI = 239

    /** Minimum fraction of pixels inside [EXPOSURE_LO, EXPOSURE_HI]. */
    const val EXPOSURE_MIN_FRACTION = 0.85

    /** Turn rate at which [stability] has fallen to one half. */
    const val STABILITY_KNEE_RAD_PER_SEC = 0.6

    /** Cap on the sharpness-vs-median boost, per the plan formula. */
    const val SHARP_CAP = 2.0

    /** Guards the sharpness ratio against a zero route median. */
    const val SCORE_EPS = 1e-6

    /** dHash resample width: 9 columns give 8 horizontal comparisons. */
    const val DHASH_W = 9

    /** dHash resample height: 8 rows give 64 bits total. */
    const val DHASH_H = 8

    /**
     * Sharpness = variance of the 3×3 Laplacian `[0,1,0; 1,−4,1; 0,1,0]`
     * over interior pixels of the decimated plane. Returns 0.0 when the
     * plane is smaller than the kernel or the buffer is short.
     */
    fun sharpness(luma: ByteArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        if (luma.size < width * height) return 0.0
        var n = 0L
        var sum = 0.0
        var sumSq = 0.0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val c = row + x
                val lap = (
                    (luma[c - width].toInt() and 0xFF) +
                        (luma[c + width].toInt() and 0xFF) +
                        (luma[c - 1].toInt() and 0xFF) +
                        (luma[c + 1].toInt() and 0xFF) -
                        4 * (luma[c].toInt() and 0xFF)
                    )
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
            }
        }
        if (n == 0L) return 0.0
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return if (variance > 0.0) variance else 0.0
    }

    /** Fraction of pixels with luma in [EXPOSURE_LO, EXPOSURE_HI]. */
    fun exposureFraction(luma: ByteArray): Double {
        if (luma.isEmpty()) return 0.0
        var ok = 0L
        for (b in luma) {
            val v = b.toInt() and 0xFF
            if (v in EXPOSURE_LO..EXPOSURE_HI) ok++
        }
        return ok.toDouble() / luma.size
    }

    /** True when [exposureFraction] meets [EXPOSURE_MIN_FRACTION]. */
    fun exposureOk(luma: ByteArray): Boolean =
        exposureFraction(luma) >= EXPOSURE_MIN_FRACTION

    /**
     * Motion stability from the rotation-vector turn rate ω (rad/s) at the
     * frame time: `1 / (1 + |ω| / 0.6)`. Still frames score 1.0; at the
     * 0.6 rad/s knee they score one half.
     */
    fun stability(omegaRadPerSec: Double): Double =
        1.0 / (1.0 + abs(omegaRadPerSec) / STABILITY_KNEE_RAD_PER_SEC)

    /**
     * 64-bit dHash over a nearest-neighbour 9×8 resample of the plane:
     * each of the 8 rows contributes 8 bits, one per adjacent-column
     * comparison (bit set when the right cell is brighter). Returns 0 when
     * the buffer is short; a uniform plane also hashes to 0.
     */
    fun dHash(luma: ByteArray, width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        if (luma.size < width * height) return 0L
        var hash = 0L
        var bit = 0
        for (gy in 0 until DHASH_H) {
            var prev = cell(luma, width, height, 0, gy)
            for (gx in 0 until DHASH_W - 1) {
                val cur = cell(luma, width, height, gx + 1, gy)
                if (cur > prev) hash = hash or (1L shl bit)
                prev = cur
                bit++
            }
        }
        return hash
    }

    /** Hamming distance between two dHash values, in bits (0..64). */
    fun hamming(a: Long, b: Long): Int = (a xor b).countOneBits()

    /**
     * Novelty of [hash] against the ledger's last kept hash:
     * `Hamming(hash, lastKept) / 64`. With no history yet ([lastKeptHash]
     * null) the frame is fully novel (1.0).
     */
    fun novelty(hash: Long, lastKeptHash: Long?): Double =
        if (lastKeptHash == null) 1.0 else hamming(hash, lastKeptHash) / 64.0

    /** Sharpness boost relative to the route median, capped at [SHARP_CAP]. */
    fun sharpFactor(sharp: Double, routeMedian: Double, eps: Double = SCORE_EPS): Double =
        min(sharp / (routeMedian + eps), SHARP_CAP)

    /**
     * Plan score: `min(sharp / (routeMedian + ε), 2) × exposureOk × stable
     * × (0.5 + 0.5·novelty)`. `exposed` is the [exposureOk] gate as 0/1: a
     * badly exposed frame scores exactly 0.0 however sharp or novel.
     */
    fun score(
        sharp: Double,
        routeMedian: Double,
        exposed: Boolean,
        stable: Double,
        novelty: Double,
        eps: Double = SCORE_EPS,
    ): Double {
        if (!exposed) return 0.0
        return sharpFactor(sharp, routeMedian, eps) * stable * (0.5 + 0.5 * novelty)
    }

    private fun cell(luma: ByteArray, width: Int, height: Int, gx: Int, gy: Int): Int {
        val sx = (gx * width / DHASH_W).coerceIn(0, width - 1)
        val sy = (gy * height / DHASH_H).coerceIn(0, height - 1)
        return luma[sy * width + sx].toInt() and 0xFF
    }
}
