package dev.shibasis.reaktor.io.adapters

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares through a [FileProvider] declared by this module, so apps get file sharing without
 * having to declare a provider of their own.
 */
class AndroidShareAdapter(activity: Activity) : ShareAdapter<Activity>(activity) {

    override suspend fun shareFile(payload: SharePayload): Boolean {
        val context = controller ?: return false

        val uri = withContext(Dispatchers.IO) {
            runCatching {
                // Staged in a dedicated cache folder that the provider's paths file exposes;
                // everything else in the sandbox stays unreachable.
                val directory = File(context.cacheDir, SHARE_DIRECTORY).apply { mkdirs() }
                val file = File(directory, payload.fileName)
                file.writeBytes(payload.bytes)
                FileProvider.getUriForFile(context, authority(context.packageName), file)
            }.getOrNull()
        } ?: return false

        val send = Intent(Intent.ACTION_SEND).apply {
            type = payload.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, payload.fileName)
            // The read grant follows clipData, not EXTRA_STREAM: without this the share sheet and
            // the app the user picks are both denied access to the file they were handed.
            clipData = ClipData.newRawUri(payload.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return runCatching {
            context.startActivity(Intent.createChooser(send, payload.title ?: payload.fileName))
        }.isSuccess
    }

    override suspend fun shareText(text: String, title: String?, subject: String?): Boolean {
        val context = controller ?: return false

        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

        return runCatching {
            context.startActivity(Intent.createChooser(send, title))
        }.isSuccess
    }

    companion object {
        private const val SHARE_DIRECTORY = "reaktor-share"

        fun authority(packageName: String) = "$packageName.reaktorfileprovider"
    }
}
