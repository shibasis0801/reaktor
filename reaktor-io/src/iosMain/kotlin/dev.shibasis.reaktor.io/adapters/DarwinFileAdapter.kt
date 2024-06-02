package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.io.adapters.FileAdapter
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

class DarwinFileAdapter(): FileAdapter<Unit>(Unit) {
    override val cacheDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSCachesDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )!!.path.orEmpty()

    override val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )!!.path.orEmpty()
}