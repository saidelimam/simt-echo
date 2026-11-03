package app.simt.echo.capture.visual

import kotlin.concurrent.Volatile

/** SPSC overwrite ring. The producer never writes the consumer's cursor. */
class VisualEgoRing(val capacity: Int = 16) {
    init { require(capacity > 0) }

    private class Slot {
        @Volatile var sequence = 0L
        // Volatile payload prevents a reader load moving beyond the final guard,
        // or a writer store moving before the in-progress guard on either target.
        @Volatile var tNs = 0L
        @Volatile var expansion = 0.0
        @Volatile var x = Double.NaN
        @Volatile var y = Double.NaN
        @Volatile var yaw = 0.0
        @Volatile var yawSigma = Double.POSITIVE_INFINITY
        @Volatile var confidence = 0.0
        @Volatile var inliers = 0
        @Volatile var hasFoe = false
        @Volatile var usability = 0.0
        @Volatile var reason = VisualReason.UNKNOWN.ordinal
    }

    private val slots = Array(capacity) { Slot() }
    @Volatile private var published = 0L
    private var consumed = 0L
    private var lastTimeNs = Long.MIN_VALUE
    var dropped = 0L
        private set

    fun publish(tNs: Long, ego: VisualEgo, usability01: Double, reason: VisualReason): Boolean {
        if (tNs <= lastTimeNs) return false
        val next = published + 1
        val slot = slots[((next - 1) % capacity).toInt()]
        slot.sequence = -next
        slot.tNs = tNs
        slot.expansion = ego.expansionPerSec
        slot.x = ego.foeX
        slot.y = ego.foeY
        slot.yaw = ego.yawResidualRadPerSec
        slot.yawSigma = ego.yawSigmaRadPerSec
        slot.confidence = ego.confidence01
        slot.inliers = ego.inliers
        slot.hasFoe = ego.hasFoe
        slot.usability = usability01
        slot.reason = reason.ordinal
        slot.sequence = next
        lastTimeNs = tNs
        published = next
        return true
    }

    /** One bounded attempt; a racing overwrite is dropped, never spun on. */
    fun poll(target: VisualMotionFrame, throughNs: Long = Long.MAX_VALUE): Boolean {
        val upper = published
        val next = maxOf(consumed + 1, upper - capacity + 1)
        if (next > upper) return false
        if (next > consumed + 1) {
            dropped += next - consumed - 1
            consumed = next - 1
        }
        val slot = slots[((next - 1) % capacity).toInt()]
        if (slot.sequence != next) return false
        val time = slot.tNs
        if (time > throughNs) return false
        val ego = target.ego
        target.tNs = time
        ego.expansionPerSec = slot.expansion
        ego.foeX = slot.x
        ego.foeY = slot.y
        ego.yawResidualRadPerSec = slot.yaw
        ego.yawSigmaRadPerSec = slot.yawSigma
        ego.confidence01 = slot.confidence
        ego.inliers = slot.inliers
        ego.hasFoe = slot.hasFoe
        target.usability01 = slot.usability
        target.reason = VisualReason.entries[slot.reason]
        if (slot.sequence != next) {
            ego.clear()
            target.tNs = 0L
            return false
        }
        consumed = next
        return true
    }
}
