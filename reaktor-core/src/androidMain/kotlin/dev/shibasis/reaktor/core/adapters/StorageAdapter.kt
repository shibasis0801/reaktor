package dev.shibasis.reaktor.core.adapters

import androidx.activity.ComponentActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Android Storage APIs are very complicated now, create abstraction using this.
 */
class AndroidStorageAdapter(activity: ComponentActivity): StorageAdapter<ComponentActivity>(activity) {
    private val FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

    override fun test(): Int {
        return 0
    }

    fun getHomeDirectory(directory: String = "Manna"): File? {
        return invoke {
            val mediaDir = externalMediaDirs
                .firstOrNull()
                ?.let { File(it, directory) }
            if (mediaDir != null && !mediaDir.exists()) {
                    mediaDir.mkdirs()
            }
            mediaDir ?: filesDir
        }
    }

    fun timeStampedFileName(extension: String): String {
        val timestamp = SimpleDateFormat(FORMAT, Locale.UK).format(System.currentTimeMillis())
        return "$timestamp.$extension"
    }
}
