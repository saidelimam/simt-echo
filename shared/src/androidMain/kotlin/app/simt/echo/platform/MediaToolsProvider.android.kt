package app.simt.echo.platform

import app.simt.echo.media.AndroidMediaTools
import app.simt.echo.media.MediaTools

actual fun createMediaTools(): MediaTools = AndroidMediaTools()
