package dev.shibasis.reaktor.media.files

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import dev.shibasis.reaktor.media.pick
import dev.shibasis.reaktor.media.displayName
import dev.shibasis.reaktor.media.durationMillis
import dev.shibasis.reaktor.media.gallery.MediaPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidFilePickerAdapter(
    activity: ComponentActivity,
) : FilePickerAdapter<ComponentActivity>(activity) {
    override suspend fun pick(mimeTypes: List<String>): MediaPick? {
        val activity = controller ?: return null
        val uri: Uri = activity.getResultFromActivity(
            ActivityResultContracts.OpenDocument(),
            mimeTypes.ifEmpty { listOf("*/*") }.toTypedArray(),
        ) ?: return null

        return withContext(Dispatchers.IO) {
            val resolver = activity.contentResolver
            val mimeType = resolver.getType(uri) ?: "application/octet-stream"
            val pick = resolver.pick(uri, mimeType, resolver.displayName(uri), readLimit) ?: return@withContext null
            val timed = mimeType.startsWith("audio/") || mimeType.startsWith("video/")
            if (timed && pick.bytes.isNotEmpty()) pick.copy(durationMillis = activity.durationMillis(uri)) else pick
        }
    }
}
