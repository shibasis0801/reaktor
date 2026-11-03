@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.security.PlatformSecurity
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

fun DarwinSealedNoticePreviews(namespace: String, appGroup: String, keychainGroup: String): SealedNoticePreviews? {
    require(namespace.matches(Regex("[a-zA-Z0-9_.-]{1,128}")))
    val manager = NSFileManager.defaultManager
    val container = manager.containerURLForSecurityApplicationGroupIdentifier(appGroup)?.path ?: return null
    val path = "$container/$namespace.previews"
    return SealedNoticePreviews(PlatformSecurity.secretBox(namespace, keychainGroup),
        read = { NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) },
        write = { value ->
            if (value == null) manager.removeItemAtPath(path, null)
            else {
                check((value as NSString).writeToFile(path, true, NSUTF8StringEncoding, null))
                check(manager.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionCompleteUntilFirstUserAuthentication), path, null))
            }
        })
}
