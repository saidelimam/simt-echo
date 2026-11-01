package app.simt.echo.platform

import platform.Foundation.NSHomeDirectory
import platform.UIKit.UIDevice

private class IosPlatform : Platform {
    override val name: String =
        "iOS ${UIDevice.currentDevice.systemVersion}"
    override val dataDir: String = NSHomeDirectory()
}

actual fun createPlatform(): Platform = IosPlatform()
