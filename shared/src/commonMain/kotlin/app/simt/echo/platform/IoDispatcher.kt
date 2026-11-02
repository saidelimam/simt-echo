package app.simt.echo.platform

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Dispatcher for blocking disk and hashing work.
 *
 * `Dispatchers.IO` is not part of the common coroutines API (it is internal
 * in the shared metadata), so common code cannot name it and the iOS targets
 * fail to compile if it tries. Each platform supplies its own.
 */
expect val platformIoDispatcher: CoroutineDispatcher
