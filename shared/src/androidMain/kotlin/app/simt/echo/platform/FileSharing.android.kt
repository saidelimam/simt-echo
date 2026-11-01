package app.simt.echo.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Path

/**
 * Android [FileSharing] (W2-07). Export exposes the app-private
 * `exports/` file through `FileProvider` (`<package>.echo.files`, see
 * `res/xml/file_paths.xml`) and opens the share sheet; import launches the
 * document picker and returns the picked bytes — a content URI is not a
 * file path, so the picker hands the caller the payload, not a location.
 * The launcher registers in the composition, bound to the Activity.
 */
class AndroidFileSharing(
    private val context: Context,
    registryOwner: ActivityResultRegistryOwner,
) : FileSharing {

    private val authority: String = "${context.packageName}.echo.files"

    private var pendingPick: CancellableContinuation<ByteArray?>? = null

    private val openDocument = registryOwner.activityResultRegistry.register(
        "echo_pick_document",
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val cont = pendingPick
        pendingPick = null
        if (cont != null) {
            val bytes = uri?.let { readDocument(it) }
            cont.resumeWith(Result.success(bytes))
        }
    }

    override suspend fun shareFile(path: Path, name: String, mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, authority, path.toFile())
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, name).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(chooser) }.isSuccess
    }

    override suspend fun pickDocument(): ByteArray? = suspendCancellableCoroutine { cont ->
        pendingPick = cont
        // A late result for a cancelled pick resumes a dead continuation (no-op);
        // clearing the slot keeps the next pick from adopting it.
        cont.invokeOnCancellation { cause -> pendingPick = null }
        openDocument.launch(arrayOf("*/*"))
    }

    private fun readDocument(uri: Uri): ByteArray =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("could not read the picked document")
}

@Composable
actual fun rememberFileSharing(): FileSharing {
    val context = LocalContext.current.applicationContext
    val owner = requireNotNull(LocalActivityResultRegistryOwner.current) {
        "FileSharing needs an Activity-scoped composition"
    }
    return remember(context, owner) { AndroidFileSharing(context, owner) }
}
