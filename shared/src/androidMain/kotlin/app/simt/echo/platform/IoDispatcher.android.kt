package app.simt.echo.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO
