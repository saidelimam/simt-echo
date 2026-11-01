package app.simt.echo.platform

import android.os.Build

private class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun createPlatform(): Platform = AndroidPlatform()
