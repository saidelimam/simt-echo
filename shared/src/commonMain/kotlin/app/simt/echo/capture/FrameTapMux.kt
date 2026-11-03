package app.simt.echo.capture

import kotlin.concurrent.Volatile

/**
 * Four fixed slots: the legacy recording sink first, then three guests.
 * Registration is thread-safe. Dispatch has one producer (the analyzer).
 * Changes made during a dispatch take effect on the next frame; snapshot
 * storage is preallocated, never retained by consumers. One failing guest
 * cannot starve the ledger or other guests. No callback runs under the lock.
 */
class FrameTapMux : FrameTap, FrameConsumer {
    private val lock = PoolLock()
    private val consumers = arrayOfNulls<FrameConsumer>(CAPACITY)
    private val dispatch = arrayOfNulls<FrameConsumer>(CAPACITY)
    @Volatile
    private var count = 0
    private var dispatching = false
    var consumerFailures = 0L
        private set

    val hasConsumers: Boolean get() = count != 0

    override fun setConsumer(consumer: FrameConsumer?) {
        lock.lock()
        try {
            var nextGuest = 1
            for (i in 1 until CAPACITY) {
                val guest = consumers[i]
                if (guest != null && guest !== consumer) consumers[nextGuest++] = guest
            }
            while (nextGuest < CAPACITY) consumers[nextGuest++] = null
            consumers[0] = consumer
            updateCount()
        } finally {
            lock.unlock()
        }
    }

    override fun addConsumer(consumer: FrameConsumer): Boolean {
        lock.lock()
        try {
            for (i in consumers.indices) if (consumers[i] === consumer) return false
            for (i in 1 until CAPACITY) {
                if (consumers[i] == null) {
                    consumers[i] = consumer
                    updateCount()
                    return true
                }
            }
            return false
        } finally {
            lock.unlock()
        }
    }

    override fun removeConsumer(consumer: FrameConsumer): Boolean {
        lock.lock()
        try {
            for (i in 1 until CAPACITY) {
                if (consumers[i] === consumer) {
                    for (j in i until CAPACITY - 1) consumers[j] = consumers[j + 1]
                    consumers[CAPACITY - 1] = null
                    updateCount()
                    return true
                }
            }
            return false
        } finally {
            lock.unlock()
        }
    }

    override fun wantsFrame(tNs: Long): Boolean {
        if (count == 0) return false
        snapshot()
        try {
            for (i in dispatch.indices) {
                val sink = dispatch[i] ?: continue
                try {
                    if (sink.wantsFrame(tNs)) return true
                } catch (_: Exception) {
                    consumerFailures++
                }
            }
            return false
        } finally {
            dispatch.fill(null)
        }
    }

    override fun onFrame(sample: FrameSample) {
        check(!dispatching) { "FrameTapMux dispatch must not be reentrant" }
        dispatching = true
        snapshot()
        try {
            for (i in dispatch.indices) {
                val sink = dispatch[i] ?: continue
                try {
                    if (sink.wantsFrame(sample.tNs)) sink.onFrame(sample)
                } catch (_: Exception) {
                    consumerFailures++
                }
            }
        } finally {
            dispatch.fill(null)
            dispatching = false
        }
    }

    private fun snapshot() {
        lock.lock()
        try {
            consumers.copyInto(dispatch)
        } finally {
            lock.unlock()
        }
    }

    private fun updateCount() {
        var active = 0
        for (i in consumers.indices) if (consumers[i] != null) active++
        count = active
    }

    companion object {
        const val CAPACITY = 4
    }
}