package app.simt.echo.platform

import android.os.Build

/**
 * Android platform. [dataDir] is the app-private files directory, supplied
 * by `EchoApplication`; the no-arg actual (previews/tests) leaves it empty
 * and storage must not be used from it.
 */
class AndroidPlatform(override val dataDir: String = "") : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun createPlatform(): Platform = AndroidPlatform()
