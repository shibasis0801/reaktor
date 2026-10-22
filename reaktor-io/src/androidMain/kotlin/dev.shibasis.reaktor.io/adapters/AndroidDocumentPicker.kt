package dev.shibasis.reaktor.io.adapters

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DocumentPicker] on the Storage Access Framework.
 *
 * Android only lets an Activity register for a result while it is being created, so build this in
 * `onCreate` and install it in the slot there; the next Activity after a rotation builds its own.
 */
class AndroidDocumentPicker(private val activity: ComponentActivity) : DocumentPicker {
    private var pending: CompletableDeferred<PickedDocument?>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val waiting = pending ?: return@registerForActivityResult
        pending = null
        // Read now: the grant on the uri is only certain while this callback runs.
        waiting.complete(uri?.let { runCatching { read(it) }.getOrNull() })
    }

    override suspend fun pick(mimeTypes: List<String>): PickedDocument? {
        if (pending != null) return null
        val result = CompletableDeferred<PickedDocument?>()
        pending = result
        val launched = runCatching {
            withContext(Dispatchers.Main) { launcher.launch(mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray()) }
        }.isSuccess
        if (!launched) {
            pending = null
            return null
        }
        return result.await()
    }

    private fun read(uri: Uri): PickedDocument? {
        val resolver = activity.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return PickedDocument(name, bytes)
    }
}
