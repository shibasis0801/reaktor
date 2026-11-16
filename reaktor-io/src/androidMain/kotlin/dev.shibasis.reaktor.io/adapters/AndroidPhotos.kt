package dev.shibasis.reaktor.io.adapters

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [PhotoPicker] and [CameraCapture] on Android: the Photo Picker and the camera app, both run by
 * the system outside this app, so neither needs a permission.
 *
 * The camera writes into this app's cache through the module's `FileProvider`; the photo is read
 * from there and the file removed, so nothing is left in the gallery or on disk.
 *
 * Build it in `Activity.onCreate` and install it in both slots there: Android only lets an
 * Activity register for results while it is being created. If Android stops the app while the
 * camera is open, the photo taken is lost and the person takes it again.
 */
class AndroidPhotos(private val activity: ComponentActivity) : PhotoPicker, CameraCapture {
    private var choosing: CompletableDeferred<PickedPhoto?>? = null
    private var taking: CompletableDeferred<PickedPhoto?>? = null
    private var shot: File? = null

    private val chooser = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val waiting = choosing ?: return@registerForActivityResult
        choosing = null
        // Read now: the grant on the uri is only certain while this callback runs.
        waiting.complete(uri?.let { runCatching { read(it) }.getOrNull() })
    }

    private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = shot
        shot = null
        val photo = if (saved && file != null) runCatching { PickedPhoto(file.readBytes()) }.getOrNull()?.takeIf { it.bytes.isNotEmpty() } else null
        file?.delete()
        taking?.complete(photo)
        taking = null
    }

    override suspend fun pick(): PickedPhoto? {
        if (choosing != null) return null
        val result = CompletableDeferred<PickedPhoto?>()
        choosing = result
        val launched = runCatching {
            withContext(Dispatchers.Main) {
                chooser.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }.isSuccess
        if (!launched) {
            choosing = null
            return null
        }
        return result.await()
    }

    override val available: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    override suspend fun capture(): PickedPhoto? {
        if (taking != null || !available) return null
        val result = CompletableDeferred<PickedPhoto?>()
        // A throw here is no camera app to open, or CAMERA declared in the manifest and not granted.
        val launched = runCatching {
            val folder = File(activity.cacheDir, FOLDER).apply { mkdirs() }
            val file = File(folder, "capture.jpg").apply { delete() }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.reaktorfileprovider", file)
            shot = file
            taking = result
            withContext(Dispatchers.Main) { camera.launch(uri) }
        }.isSuccess
        if (!launched) {
            shot?.delete()
            shot = null
            taking = null
            return null
        }
        return result.await()
    }

    private fun read(uri: Uri): PickedPhoto? =
        activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.isNotEmpty() }?.let(::PickedPhoto)

    private companion object {
        /** In reaktor_share_paths.xml, apart from the files the share sheet stages. */
        const val FOLDER = "reaktor-capture"
    }
}
