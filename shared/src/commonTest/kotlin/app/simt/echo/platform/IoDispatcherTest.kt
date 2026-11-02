package app.simt.echo.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/** Each platform supplies the blocking-I/O dispatcher that common code cannot name. */
class IoDispatcherTest {

    @Test
    fun runsBlockingWorkAndReturnsItsResult() = runTest {
        assertEquals(42, withContext(platformIoDispatcher) { 40 + 2 })
    }

    @Test
    fun isNeverTheMainDispatcher() {
        // Disk reads on Main are exactly what W10-02 removed; the seam must not hand it back.
        assertNotSame(Dispatchers.Main, platformIoDispatcher)
    }
}
