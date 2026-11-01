package app.simt.echo.platform

import platform.UIKit.UIDevice

private class IosPlatform : Platform {
    override val name: String =
        "iOS ${UIDevice.currentDevice.systemVersion}"
}

actual fun createPlatform(): Platform = IosPlatform()
