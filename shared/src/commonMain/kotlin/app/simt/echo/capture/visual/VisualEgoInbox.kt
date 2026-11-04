package app.simt.echo.capture.visual

import kotlin.concurrent.Volatile

/**
 * SPSC overwrite inbox for visual egomotion crossing from the analysis thread
 * to the IMU thread. The producer only copies primitives; the consumer owns
 * [VisualEgo] and [PdrEngine] state. A racing overwrite drops the oldest
 * unread sample rather than blocking the sensor thread.
 */
internal class VisualEgoInbox(val capacity: Int = 8) {
    init { require(capacity > 0) }

    internal class Sample {
        val ego = VisualEgo()
        var tNs = 0L
        var wallNowNs = 0L
        var reason = VisualReason.NONE
    }

    private class Slot {
        @Volatile var sequence = 0L
        @Volatile var tNs = 0L
        @Volatile var wallNowNs = 0L
        @Volatile var expansion = 0.0
        @Volatile var x = Double.NaN
        @Volatile var y = Double.NaN
        @Volatile var yaw = 0.0
        @Volatile var yawSigma = Double.POSITIVE_INFINITY
        @Volatile var expansionSigma = Double.POSITIVE_INFINITY
        @Volatile var confidence = 0.0
        @Volatile var inliers = 0
        @Volatile var hasFoe = false
        @Volatile var reason = VisualReason.NONE.ordinal
    }

    private val slots = Array(capacity) { Slot() }
    @Volatile private var published = 0L
    private var consumed = 0L
    private var lastTimeNs = Long.MIN_VALUE
    var dropped = 0L
        private set

    fun reset() {
        published = 0L
        consumed = 0L
        lastTimeNs = Long.MIN_VALUE
        dropped = 0L
        for (slot in slots) slot.sequence = 0L
    }

    fun publish(tNs: Long, wallNowNs: Long, ego: VisualEgo, reason: VisualReason): Boolean {
        if (tNs <= lastTimeNs) return false
        val next = published + 1
        val slot = slots[((next - 1) % capacity).toInt()]
        slot.sequence = -next
        slot.tNs = tNs
        slot.wallNowNs = wallNowNs
        slot.expansion = ego.expansionPerSec
        slot.x = ego.foeX
        slot.y = ego.foeY
        slot.yaw = ego.yawResidualRadPerSec
        slot.yawSigma = ego.yawSigmaRadPerSec
        slot.expansionSigma = ego.expansionSigmaPerSec
        slot.confidence = ego.confidence01
        slot.inliers = ego.inliers
        slot.hasFoe = ego.hasFoe
        slot.reason = reason.ordinal
        slot.sequence = next
        lastTimeNs = tNs
        published = next
        return true
    }

    /** One bounded attempt; a racing overwrite is dropped, never spun on. */
    fun poll(target: Sample): Boolean {
        val upper = published
        val next = maxOf(consumed + 1, upper - capacity + 1)
        if (next > upper) return false
        if (next > consumed + 1) {
            dropped += next - consumed - 1
            consumed = next - 1
        }
        val slot = slots[((next - 1) % capacity).toInt()]
        if (slot.sequence != next) return false
        target.tNs = slot.tNs
        target.wallNowNs = slot.wallNowNs
        target.reason = VisualReason.entries[slot.reason]
        val ego = target.ego
        ego.expansionPerSec = slot.expansion
        ego.foeX = slot.x
        ego.foeY = slot.y
        ego.yawResidualRadPerSec = slot.yaw
        ego.yawSigmaRadPerSec = slot.yawSigma
        ego.expansionSigmaPerSec = slot.expansionSigma
        ego.confidence01 = slot.confidence
        ego.inliers = slot.inliers
        ego.hasFoe = slot.hasFoe
        if (slot.sequence != next) {
            ego.clear()
            target.tNs = 0L
            return false
        }
        consumed = next
        return true
    }
}
