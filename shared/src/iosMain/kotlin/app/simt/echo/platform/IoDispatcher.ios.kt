package app.simt.echo.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO
