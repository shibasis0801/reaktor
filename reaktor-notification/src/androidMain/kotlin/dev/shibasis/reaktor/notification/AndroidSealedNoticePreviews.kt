package dev.shibasis.reaktor.notification

import android.content.Context
import android.util.AtomicFile
import dev.shibasis.reaktor.security.PlatformSecurity
import java.io.File

fun AndroidSealedNoticePreviews(context: Context, namespace: String): SealedNoticePreviews {
    require(namespace.matches(Regex("[a-zA-Z0-9_.-]{1,128}")))
    val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, "$namespace.previews"))
    return SealedNoticePreviews(PlatformSecurity.secretBox(namespace),
        read = { if (file.baseFile.exists()) file.openRead().bufferedReader().use { it.readText() } else null },
        write = { value ->
            if (value == null) file.delete()
            else {
                val stream = file.startWrite()
                try {
                    stream.write(value.encodeToByteArray())
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }
        })
}
