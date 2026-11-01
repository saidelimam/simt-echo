package app.simt.echo.platform

import androidx.compose.ui.graphics.ImageBitmap

// iOS is out of v1 scope (plan W5-03 Accepted): Review shows text-only
// cards until a platform decoder lands with the iOS build.

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = null
