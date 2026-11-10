package dev.shibasis.reaktor.io.adapters

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [FolderChooser] on the Storage Access Framework's tree picker. The grant is taken as persistable,
 * so [AndroidFolderWriter] can still write there after the app restarts. Build it in `onCreate`.
 */
class AndroidFolderChooser(private val activity: ComponentActivity) : FolderChooser {
    private var pending: CompletableDeferred<ChosenFolder?>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val waiting = pending ?: return@registerForActivityResult
        pending = null
        waiting.complete(uri?.let { runCatching { keep(it) }.getOrNull() })
    }

    override suspend fun choose(): ChosenFolder? {
        if (pending != null) return null
        val result = CompletableDeferred<ChosenFolder?>()
        pending = result
        val launched = runCatching { withContext(Dispatchers.Main) { launcher.launch(null) } }.isSuccess
        if (!launched) {
            pending = null
            return null
        }
        return result.await()
    }

    private fun keep(tree: Uri): ChosenFolder {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        activity.contentResolver.takePersistableUriPermission(tree, flags)
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = activity.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: tree.lastPathSegment.orEmpty()
        return ChosenFolder(tree.toString(), name)
    }
}

/** [FolderWriter] for folders chosen with [AndroidFolderChooser], through the application's context. */
class AndroidFolderWriter(context: Context) : FolderWriter {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun write(folder: ChosenFolder, fileName: String, mimeType: String, bytes: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val tree = Uri.parse(folder.id)
                val target = child(tree, fileName)
                    ?: DocumentsContract.createDocument(resolver, root(tree), mimeType, fileName)
                    ?: return@runCatching false
                // "wt": truncate, so a shorter file never keeps the tail of a longer one.
                resolver.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: return@runCatching false
                true
            }.getOrDefault(false)
        }

    override suspend fun list(folder: ChosenFolder): List<String>? = withContext(Dispatchers.IO) {
        runCatching { children(Uri.parse(folder.id)).map { it.first } }.getOrNull()
    }

    override suspend fun delete(folder: ChosenFolder, fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val target = child(Uri.parse(folder.id), fileName) ?: return@runCatching false
            DocumentsContract.deleteDocument(resolver, target)
        }.getOrDefault(false)
    }

    private fun root(tree: Uri) = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun child(tree: Uri, name: String): Uri? =
        children(tree).firstOrNull { it.first == name }?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.second) }

    /** (display name, document id) of each child of the tree's root. */
    private fun children(tree: Uri): List<Pair<String, String>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        return resolver.query(uri, columns, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }.orEmpty()
    }
}
