package app.simt.echo.host

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Wiring probe for the W6-03/W7-02 headless UI gate: proves the
 * `androidHostTest` source set compiles and runs under
 * `:shared:testAndroidHostTest`. Real behaviour tests live beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class HostWiringTest {

    @Test
    fun hostSourceSetRunsUnderRobolectric() {
        assertTrue(android.os.Build.VERSION.SDK_INT > 0)
    }
}
