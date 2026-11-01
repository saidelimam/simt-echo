package app.simt.echo.platform

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes image bytes (Review keyframe heroes, start photo) for display.
 * Null when the bytes are not a decodable image — including on iOS, where
 * decoding is out of v1 scope (plan W5-03 Accepted).
 */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
