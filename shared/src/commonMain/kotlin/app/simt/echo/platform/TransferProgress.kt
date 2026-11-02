package app.simt.echo.platform

/**
 * Additive byte-count progress on the transfer seam (plan W13-14).
 *
 * Direction-agnostic: on the offering phone it counts bytes served to the
 * peer; on the fetching phone, bytes received so far. Either way nothing
 * is claimed before bytes move — UI particle flow binds to the measured
 * rate and freezes at 0 bytes/s (honest light).
 *
 * Never touches the wire format, keys or tokens: this is a local
 * observation of a transfer already in flight. Monotonic per transfer and
 * ends at ([totalBytes], [totalBytes]).
 */
data class TransferProgress(
    val bytesSent: Long = 0L,
    val totalBytes: Long = 0L,
) {
    /** 0→1 share of the transfer known to have moved; 0 when total unknown. */
    val fraction: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (bytesSent.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        }

    /** True once every known byte has moved. */
    val isComplete: Boolean get() = totalBytes > 0L && bytesSent >= totalBytes
}

/**
 * Measured throughput between two progress samples, in bytes/s.
 * Returns 0 when no time passed or no bytes moved — the honest-light
 * binding treats that as frozen, never as motion.
 */
fun bytesPerSecond(
    before: TransferProgress,
    beforeMs: Long,
    after: TransferProgress,
    afterMs: Long,
): Double {
    val dtMs = afterMs - beforeMs
    if (dtMs <= 0L) return 0.0
    val moved = after.bytesSent - before.bytesSent
    if (moved <= 0L) return 0.0
    return moved.toDouble() * 1000.0 / dtMs.toDouble()
}
