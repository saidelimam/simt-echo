package app.simt.echo.capture.visual

/**
 * Reused compact output. Positions and translation-only per-frame flows are
 * focal-length normalised; multiply by [fPx] for 120x90-level pixels.
 * Only indices below [count] are valid. Consumers must finish synchronously.
 */
class BlockFlow(capacity: Int = 12) {
    val x = DoubleArray(capacity)
    val y = DoubleArray(capacity)
    val dx = DoubleArray(capacity)
    val dy = DoubleArray(capacity)
    val texture = DoubleArray(capacity)
    val quality = DoubleArray(capacity)
    var count = 0
    var deltaSeconds = 0.0
    var fPx = 0.0
}
