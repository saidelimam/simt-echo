package app.simt.echo.ui.atmosphere

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// Pure edge-band starfield model, docs/research/cosmos-echo-freeze.md §9.3
// ([P-T starfield] [P-R C11]): stars live ONLY in the outer-20% edge band —
// the centre 60%x60% stays clear — cached as one layer, absent from the
// accessibility tree. This file is allocation-free UI logic: deterministic
// generation (seeded) so the background can remember one list per size and
// never rebuild it in the draw phase.
//
// Frozen numbers: screens round(area/7500) clamped 24-160,
// dialogs round(area/10000) clamped 12-72, bucket 160, >=70% ink,
// <=10% any one accent, <=3 spikes, 90-160 s drift <=1.5% of short edge,
// low power draws 40%.

/** Background density flavour. Dialogs use the sparse dialog formula. */
enum class CosmosVariant {
    Home,
    Panel,
    Dialog,
    Gate,
}

/**
 * One star in unit space. [fx]/[fy] are fractions of the canvas size and
 * may overscan slightly outside [0, 1] so small parallax rotations never
 * reveal empty corners. [kind]: 0 = ink dust, 1 = ink bright, 2/3 = accent
 * channels (each <=10%). [spike] marks at most 3 bright stars drawn with a
 * cross glint.
 */
data class CosmosStar(
    val fx: Float,
    val fy: Float,
    val sizeFactor: Float,
    val alpha: Float,
    val kind: Int,
    val twinkleOffset: Float,
    val spike: Boolean,
)

const val COSMOS_SCREEN_BUCKET = 160
const val COSMOS_DIALOG_BUCKET = 72

internal const val COSMOS_EDGE_CLEAR_MIN = 0.2f
internal const val COSMOS_EDGE_CLEAR_MAX = 0.8f

/** Frozen count formula: screens round(area/7500) in 24-160, dialogs round(area/10000) in 12-72. */
fun starCountForArea(areaPx: Float, dialog: Boolean): Int {
    val raw = if (dialog) (areaPx / 10_000f + 0.5f).toInt() else (areaPx / 7_500f + 0.5f).toInt()
    return if (dialog) raw.coerceIn(12, COSMOS_DIALOG_BUCKET) else raw.coerceIn(24, COSMOS_SCREEN_BUCKET)
}

/**
 * Generates the edge-band starfield for a canvas of [widthPx] x [heightPx].
 * Rejection-samples uniform points and keeps only those outside the centre
 * clear zone; deterministic for a given [seed] + [variant]. Low power
 * ([lowPower], e.g. STATIC tier) draws 40% of the frozen count.
 */
fun generateCosmosStars(
    widthPx: Float,
    heightPx: Float,
    variant: CosmosVariant,
    lowPower: Boolean,
    seed: Long = 42L,
): List<CosmosStar> {
    require(widthPx > 0f && heightPx > 0f) { "canvas must be non-empty" }
    val dialog = variant == CosmosVariant.Dialog
    val full = starCountForArea(widthPx * heightPx, dialog)
    val count = if (lowPower) (full * 0.4f).toInt() else full
    val random = Random(seed + variant.ordinal)
    val stars = ArrayList<CosmosStar>(count)
    var accentFlip = false
    var spikes = 0
    while (stars.size < count) {
        // Overscan band: rotation around the centre must not uncover corners.
        val fx = random.nextFloat() * 1.24f - 0.12f
        val fy = random.nextFloat() * 1.24f - 0.12f
        if (fx >= COSMOS_EDGE_CLEAR_MIN && fx <= COSMOS_EDGE_CLEAR_MAX &&
            fy >= COSMOS_EDGE_CLEAR_MIN && fy <= COSMOS_EDGE_CLEAR_MAX
        ) {
            continue
        }
        val index = stars.size
        val kind = when {
            index % 10 == 0 -> {
                accentFlip = !accentFlip
                if (accentFlip) 2 else 3
            }
            index % 3 == 0 -> 1
            else -> 0
        }
        val spike = kind >= 2 && spikes < 3
        if (spike) spikes++
        stars.add(
            CosmosStar(
                fx = fx,
                fy = fy,
                sizeFactor = when (kind) {
                    2, 3 -> 1.6f + random.nextFloat() * 0.8f
                    1 -> 1.0f + random.nextFloat() * 0.5f
                    else -> 0.5f + random.nextFloat() * 0.4f
                },
                alpha = when (kind) {
                    2, 3 -> 0.95f
                    1 -> 0.55f
                    else -> 0.34f
                },
                kind = kind,
                twinkleOffset = random.nextFloat(),
                spike = spike,
            ),
        )
    }
    return stars
}

/**
 * Per-frame star position with manual parallax rotation (multiply-adds
 * only, no allocation): rotates the centre-relative offset by [angleRad]
 * and re-centres. The draw phase calls this instead of a capturing
 * rotate{} block so no lambda instance is created per frame.
 */
fun rotatedStarPosition(
    star: CosmosStar,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    angleRad: Float,
    out: FloatArray,
): FloatArray {
    val dx = (star.fx - 0.5f) * canvasWidthPx
    val dy = (star.fy - 0.5f) * canvasHeightPx
    val cosA = cos(angleRad)
    val sinA = sin(angleRad)
    out[0] = canvasWidthPx / 2f + dx * cosA - dy * sinA
    out[1] = canvasHeightPx / 2f + dx * sinA + dy * cosA
    return out
}
