package dev.shibasis.reaktor.io.adapters

import dev.shibasis.reaktor.io.adapters.FileAdapter
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

class DarwinFileAdapter(): FileAdapter<Unit>(Unit) {
    override fun getCacheDirectory(): String {
        return NSFileManager.defaultManager.URLForDirectory(
            directory = NSCachesDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )!!.path.orEmpty()
    }
}