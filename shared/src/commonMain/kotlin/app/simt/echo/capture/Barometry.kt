package app.simt.echo.capture

import kotlin.math.pow

/**
 * Shared barometric height (plan W12-08: second use of the §6.3 formula,
 * extracted from `Segmenter` so capture and navigation agree exactly).
 *
 * Relative by construction: [p0] is a leg-start reading, never a
 * sea-level assumption, so weather drift between days never leaks in.
 */
internal fun hPaToM(p: Double, p0: Double): Double =
    44330.0 * (1.0 - (p / p0).pow(1.0 / 5.255))
