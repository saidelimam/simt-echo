package app.simt.echo.transfer

import app.simt.echo.platform.TransferProgress
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.readAvailable
import okio.Buffer

/** Client stream quantum: one progress emit per chunk received. */
internal const val HTTP_PROGRESS_CHUNK_BYTES = 64 * 1024

/**
 * Reads [HttpResponse] in chunks, invoking [onChunk] with measured receive
 * progress per chunk and a final emit exactly at ([size], [size])
 * (W13-14). The total is the declared Content-Length when the server sent
 * one, else bytes so far.
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
        onChunk(TransferProgress(received, declared ?: received))
    }
    val bytes = sink.readByteArray()
    val total = bytes.size.toLong()
    onChunk(TransferProgress(total, total))
    return bytes
}
