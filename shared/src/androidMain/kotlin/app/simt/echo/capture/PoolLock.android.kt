package app.simt.echo.capture

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** [PoolLock] on a JVM [ReentrantLock]. */
internal actual class PoolLock actual constructor() {
    private val lock = ReentrantLock()

    actual fun lock() = lock.lock()
    actual fun unlock() = lock.unlock()
    actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
