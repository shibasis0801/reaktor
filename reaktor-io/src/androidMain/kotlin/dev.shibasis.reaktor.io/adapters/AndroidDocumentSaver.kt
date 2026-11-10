package dev.shibasis.reaktor.io.adapters

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [DocumentSaver] on the Storage Access Framework's create dialog. Build it in `onCreate`, like
 * [AndroidDocumentPicker]. The contract is this file's own, not `CreateDocument(mimeType)`, whose
 * type is fixed when it is registered: here each call names its own.
 */
class AndroidDocumentSaver(private val activity: ComponentActivity) : DocumentSaver {
    private var pending: CompletableDeferred<Uri?>? = null

    private val launcher = activity.registerForActivityResult(Create) { uri ->
        val waiting = pending ?: return@registerForActivityResult
        pending = null
        waiting.complete(uri)
    }

    override suspend fun save(fileName: String, mimeType: String, bytes: ByteArray): String? {
        if (pending != null) return null
        val chosen = CompletableDeferred<Uri?>()
        pending = chosen
        val launched = runCatching { withContext(Dispatchers.Main) { launcher.launch(fileName to mimeType) } }.isSuccess
        if (!launched) {
            pending = null
            return null
        }
        // A created document's grant outlasts the callback, unlike a picked one's: written here.
        val uri = chosen.await() ?: return null
        return withContext(Dispatchers.IO) { runCatching { write(uri, bytes) }.getOrNull() }
    }

    private fun write(uri: Uri, bytes: ByteArray): String? {
        val resolver = activity.contentResolver
        resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: return null
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
    }

    private object Create : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second)
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
    }
}
