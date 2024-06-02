package dev.shibasis.reaktor.io.adapters

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract.Document
import androidx.activity.ComponentActivity
import dev.shibasis.reaktor.framework.Feature
import dev.shibasis.reaktor.io.adapters.FileAdapter
import kotlinx.io.asSource
import kotlinx.io.buffered
import okio.Okio
import okio.source
import java.io.File

class AndroidFileAdapter(activity: Activity): FileAdapter<Activity>(activity) {
    override val cacheDirectory = controller?.cacheDir?.absolutePath ?: ""
    override val documentDirectory = controller?.filesDir?.absolutePath ?: ""
}

fun Uri.toFileFromContent(activity: Activity): File? {
    val inputStream = activity.contentResolver?.openInputStream(this) ?: return null
    val cursor = activity.contentResolver.query(this, null, null, null, null);
    val name = cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            it.getString(nameIndex)
        }
        else null
    }
    val file = File(File(Feature.File?.cacheDirectory!!), name!!)
    file.outputStream().use { outputStream ->
        outputStream.buffered()
        inputStream.copyTo(outputStream)
    }
    return file
}






