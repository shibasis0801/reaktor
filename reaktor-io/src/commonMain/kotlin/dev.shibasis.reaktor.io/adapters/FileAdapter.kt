package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/*
https://chat.openai.com/c/10795b61-e0ea-4e17-afdf-10f4079ac440

========================================================================================================
1. Home Directory (iOS) vs. App's Root Directory (Android)
> iOS:
Each iOS app has a home directory.
This is the top-level directory for the app and is similar to the app's root directory in Android.
It contains several subdirectories for different data types (Documents, Library, tmp, etc.).

> Android Analogy: Similar to getFilesDir() in Android, which returns the path to the internal directory for your app.
========================================================================================================
2. Documents Directory (iOS) vs. App-Specific Directory on External Storage (Android)
> iOS: The Documents directory is used to store user-generated content and important data files.
It's backed up by iCloud by default.

> Android Analogy: Similar to the external storage directory returned by getExternalFilesDir(),
where you might store user-generated content or other important files.
However, unlike Android, iOS apps cannot freely access the entire file system but are limited to their sandbox environment.

========================================================================================================
3. Library Directory (iOS)
iOS: This is divided into several subdirectories, but the most relevant are:

Preferences: Stores app preference files.
Caches: Stores files that can be regenerated but need persistent storage.
Android Analogy: The Preferences directory is similar to using SharedPreferences in Android for storing user preferences.
The Caches directory is akin to the cache directory in Android, accessed via getCacheDir().

========================================================================================================
4. tmp Directory (iOS) vs. Cache Directory (Android)
iOS: The tmp directory is used to store temporary files that do not need persistence beyond the current session.
Files in this directory can be cleared when the app is not running.

Android Analogy: Similar to the cache directory in Android (getCacheDir()), where temporary files are stored.
Like iOS, Android can also clear this cache when the device is low on storage.

========================================================================================================
5. Application Support Directory (iOS)
iOS: Used for storing files that support the app and are not user data files.
These could be database files, configuration files, etc. Not typically visible to the user.

Android Analogy: Similar to the internal storage in Android (getFilesDir()),
where you store files that are important for the functioning of the app but aren't user-generated content.

========================================================================================================
6. Photo Library (iOS) vs. External Storage (Android)
iOS: Accessing the Photo Library requires user permission. Used for storing images and videos that the app might save or use.

Android Analogy: Similar to accessing the external storage on Android to save pictures or videos. Requires user permission on both platforms.


========================================================================================================
Key Differences
Sandbox Environment: iOS maintains a strict sandbox environment for each app, meaning an app has access only to its own directory and specific system directories (like the Photo Library) with proper permissions.
File System Access: Unlike Android, where apps can request permission to access the entire file system (scoped storage notwithstanding), iOS apps are generally confined to their sandbox and specific shared directories like the Photo Library.
iCloud Backup: Certain iOS directories (like Documents) are backed up to iCloud by default, unlike Android's more manual approach to backup and restore.
 */

fun bufferedSink(path: String, actions: Sink.() -> Unit) {
    val bufferedSink = SystemFileSystem.sink(Path(path)).buffered()
    actions(bufferedSink)
    bufferedSink.close()
}

fun bufferedSource(path: String, actions: (source: Source) -> Unit) {
    val bufferedSource = SystemFileSystem.source(Path(path)).buffered()
    actions(bufferedSource)
    bufferedSource.close()
}

abstract class FileAdapter<Controller>(controller: Controller) : Adapter<Controller>(controller) {
    abstract val cacheDirectory: String
    abstract val documentDirectory: String
    fun readBinaryFile(path: String): ByteArray? {
        val actualPath = Path(path)
        if (!SystemFileSystem.exists(actualPath)) return null

        val bufferedSource = SystemFileSystem.source(actualPath).buffered()
        val data = bufferedSource.readByteArray()
        bufferedSource.close()
        return data
    }
    fun readTextFile(path: String): String? {
        val actualPath = Path(path)
        if (!SystemFileSystem.exists(actualPath)) return null

        val bufferedSource = SystemFileSystem.source(actualPath).buffered()
        val data = bufferedSource.readString()
        bufferedSource.close()
        return data
    }
    fun writeTextFile(path: String, data: String) {
        val bufferedSink = SystemFileSystem.sink(Path(path)).buffered()
        bufferedSink.writeString(data)
        bufferedSink.close()
    }
    fun writeBinaryFile(path: String, data: ByteArray) {
        val bufferedSink = SystemFileSystem.sink(Path(path)).buffered()
        bufferedSink.write(data)
        bufferedSink.close()
    }

    fun bufferedWriteBinaryFile(source: Source, outputPath: String) {
        val bufferedSink = SystemFileSystem.sink(Path(outputPath)).buffered()
//        bufferedSink.write(source, source.)
        bufferedSink.close()
    }
}

var Feature.File by CreateSlot<FileAdapter<*>>()































