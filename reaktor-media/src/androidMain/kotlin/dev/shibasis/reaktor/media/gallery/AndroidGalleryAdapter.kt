package dev.shibasis.reaktor.media.gallery

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.shibasis.reaktor.core.extensions.getResultFromActivity
import dev.shibasis.reaktor.media.bytes
import dev.shibasis.reaktor.media.displayName
import dev.shibasis.reaktor.media.withVideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidGalleryAdapter(
    activity: ComponentActivity,
) : GalleryAdapter<ComponentActivity>(activity) {
    override suspend fun pickImage(): MediaPick? {
        val activity = controller ?: return null
        val uri: Uri? = activity.getResultFromActivity(
            ActivityResultContracts.PickVisualMedia(),
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
        if (uri == null) return null

        val resolver = activity.contentResolver
        val mime = resolver.getType(uri) ?: "image/*"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return MediaPick(bytes = bytes, mimeType = mime, suggestedName = uri.lastPathSegment)
    }

    override suspend fun pickVideo(): MediaPick? {
        val activity = controller ?: return null
        val uri: Uri = activity.getResultFromActivity(
            ActivityResultContracts.PickVisualMedia(),
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
        ) ?: return null

        return withContext(Dispatchers.IO) {
            val resolver = activity.contentResolver
            val bytes = resolver.bytes(uri) ?: return@withContext null
            MediaPick(
                bytes = bytes,
                mimeType = resolver.getType(uri) ?: "video/mp4",
                suggestedName = resolver.displayName(uri),
            ).withVideoDetails(activity, uri)
        }
    }
}
