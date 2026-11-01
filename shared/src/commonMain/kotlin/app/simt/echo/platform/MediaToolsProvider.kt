package app.simt.echo.platform

import app.simt.echo.media.MediaTools

/**
 * Frame extraction + cropping for Review keyframes and manual landmark
 * crops (W2-01), selected once per graph like the other platform seams.
 * iOS is out of v1 scope: the iOS actual fails loudly when called.
 */
expect fun createMediaTools(): MediaTools
