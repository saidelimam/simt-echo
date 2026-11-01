package app.simt.echo.platform

import androidx.compose.runtime.Composable
import app.simt.echo.media.MediaTools

/**
 * Frame extraction, cropping and clip cutting (W2-01, W3-01). A composable
 * seam like the other device services: Media3's Transformer must run with
 * an Android Context, so the instance is remembered in the composition
 * rather than the graph. iOS is out of v1 scope: the iOS actual fails
 * loudly when called.
 */
@Composable
expect fun rememberMediaTools(): MediaTools
