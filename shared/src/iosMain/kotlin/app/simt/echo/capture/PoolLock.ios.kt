package app.simt.echo.capture

import platform.Foundation.NSRecursiveLock

/** [PoolLock] on an `NSRecursiveLock` (iOS never analyses in v1). */
internal actual class PoolLock actual constructor() {
    private val lock = NSRecursiveLock()

    actual fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
