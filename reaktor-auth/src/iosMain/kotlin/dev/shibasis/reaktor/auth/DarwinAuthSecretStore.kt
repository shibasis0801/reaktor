@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.db.AuthSecretStore
import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Security.*

/** A private, non-synchronizing Keychain item, available only on this unlocked device. */
class DarwinAuthSecretStore(private val service: String) : AuthSecretStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        withQuery { query -> memScoped {
            CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val result = alloc<CFTypeRefVar>()
            result.value = null
            val status = SecItemCopyMatching(query, result.ptr)
            if (status == errSecItemNotFound) return@memScoped null
            check(status == errSecSuccess) { "Could not read protected auth session ($status)" }
            val data = requireNotNull(result.value) as CFDataRef
            try {
                val size = CFDataGetLength(data).toInt()
                if (size == 0) "" else requireNotNull(CFDataGetBytePtr(data)).readBytes(size).decodeToString()
            } finally { CFRelease(data) }
        } }
    }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.Default) {
        val bytes = value.encodeToByteArray()
        require(bytes.isNotEmpty()) { "Protected auth record must not be empty" }
        bytes.usePinned { pinned ->
            val data = requireNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()))
            val attributes = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
            try {
                CFDictionarySetValue(attributes, kSecValueData, data)
                CFDictionarySetValue(attributes, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                withQuery { query ->
                    val updated = SecItemUpdate(query, attributes)
                    val status = if (updated == errSecItemNotFound) {
                        CFDictionarySetValue(query, kSecValueData, data)
                        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                        SecItemAdd(query, null)
                    } else updated
                    check(status == errSecSuccess) { "Could not persist protected auth session ($status)" }
                }
            } finally {
                CFRelease(attributes)
                CFRelease(data)
            }
        }
    }

    private fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T = memScoped {
        val query = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
        val serviceValue = requireNotNull(CFStringCreateWithCString(null, service, kCFStringEncodingUTF8))
        val account = requireNotNull(CFStringCreateWithCString(null, "session-v1", kCFStringEncodingUTF8))
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, serviceValue)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            block(query)
        } finally {
            CFRelease(query)
            CFRelease(account)
            CFRelease(serviceValue)
        }
    }
}
