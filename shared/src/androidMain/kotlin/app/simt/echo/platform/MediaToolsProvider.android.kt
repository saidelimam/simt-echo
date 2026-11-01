package app.simt.echo.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.simt.echo.media.AndroidMediaTools
import app.simt.echo.media.MediaTools

@Composable
actual fun rememberMediaTools(): MediaTools {
    val context = LocalContext.current.applicationContext
    return remember(context) { AndroidMediaTools(context) }
}
