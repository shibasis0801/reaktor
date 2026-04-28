package dev.shibasis.reaktor.media.gallery

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import dev.shibasis.reaktor.core.extensions.getResultFromActivity

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
}
