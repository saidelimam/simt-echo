package app.simt.echo.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import app.simt.echo.platform.decodeImage

/**
 * Decoded JPEG/PNG bytes as an [Image], or [fallback] when [bytes] is null
 * or undecodable. Review passes "No keyframe yet"; Navigate hides the slot
 * instead of drawing a broken image.
 */
@Composable
fun CropImage(
    bytes: ByteArray?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    val bitmap = remember(bytes) { bytes?.let(::decodeImage) }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxWidth(),
        )
    } else {
        fallback()
    }
}
