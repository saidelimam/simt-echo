package app.simt.echo.transfer

import app.simt.echo.platform.TransferProgress
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.Buffer

/** Client stream quantum: one progress emit per chunk received. */
internal const val HTTP_PROGRESS_CHUNK_BYTES = 64 * 1024

/**
 * Reads [HttpResponse] in chunks, invoking [onChunk] with measured receive
 * progress per chunk and a final emit exactly at ([size], [size])
 * (W13-14). The total is the declared Content-Length when the server sent
 * one; without it the total stays 0 (indeterminate: fraction 0, never
 * complete) until the final emit, so nothing reads as finished mid-stream.
 *
 * Uses Ktor's [InternalAPI] chunk read: the public surface offers only
 * whole-body reads, which cannot observe bytes in flight. Scoped to this
 * helper — a Ktor upgrade that moves it fails the build, never silently.
 */
@OptIn(InternalAPI::class)
internal suspend fun HttpResponse.readWithProgress(
    onChunk: (TransferProgress) -> Unit,
): ByteArray {
    val declared = headers[HttpHeaders.ContentLength]?.toLongOrNull()
    val channel = bodyAsChannel()
    val sink = Buffer()
    val buf = ByteArray(HTTP_PROGRESS_CHUNK_BYTES)
    var received = 0L
    while (true) {
        val n = channel.readAvailable(buf, 0, buf.size)
        if (n < 0) break
        sink.write(buf, 0, n)
        received += n
        onChunk(TransferProgress(received, declared ?: 0L))
    }
    val bytes = sink.readByteArray()
    val total = bytes.size.toLong()
    onChunk(TransferProgress(total, total))
    return bytes
}

/** Progress samples buffered for a slow collector before the oldest is dropped. */
private const val PROGRESS_BUFFER = 64

/**
 * Progress channel for a transport. [BufferOverflow.DROP_OLDEST] keeps the
 * newest samples, so a collector that falls behind still receives the
 * final (total, total) rather than losing it to a full buffer.
 */
internal fun newProgressFlow(): MutableSharedFlow<TransferProgress> =
    MutableSharedFlow(extraBufferCapacity = PROGRESS_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
