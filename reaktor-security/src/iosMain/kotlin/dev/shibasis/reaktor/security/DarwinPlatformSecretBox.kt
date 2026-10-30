@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shibasis.reaktor.security

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.*
import platform.Security.*

class DarwinPlatformSecretBox(private val serviceName: String, private val accessGroup: String? = null) : PlatformSecretBox {
    init {
        require(serviceName.isNotBlank() && serviceName.length <= 256)
        require(accessGroup == null || accessGroup.isNotBlank())
    }

    override suspend fun seal(recordType: SecretRecordType, recordId: String, plaintext: ByteArray,
        aad: ByteArray, options: SecretAccessOptions): SealedSecret = withContext(Dispatchers.Default) {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        val key = root()
        try { sealSecret(key, serviceName, recordType, recordId, plaintext, aad) }
        finally { key.fill(0) }
    }

    override suspend fun open(recordType: SecretRecordType, recordId: String, sealed: SealedSecret,
        aad: ByteArray, options: SecretAccessOptions): ByteArray = withContext(Dispatchers.Default) {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        val key = requireNotNull(readRoot()) { "The protected root key is unavailable" }
        try { openSecret(key, serviceName, recordType, recordId, sealed, aad) }
        finally { key.fill(0) }
    }

    private fun root(): ByteArray {
        readRoot()?.let { return it }
        val candidate = SecurityEngineFactory.storageKey()
        try {
            val added = candidate.usePinned { pinned ->
                val data = requireNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), 32))
                try { withQuery { query ->
                    CFDictionarySetValue(query, kSecValueData, data)
                    CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
                    SecItemAdd(query, null)
                } } finally { CFRelease(data) }
            }
            if (added == errSecSuccess) return candidate.copyOf()
            check(added == errSecDuplicateItem) { "Could not create protected security key ($added)" }
            return requireNotNull(readRoot())
        } finally { candidate.fill(0) }
    }

    private fun readRoot(): ByteArray? = withQuery { query -> memScoped {
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        val result = alloc<CFTypeRefVar>()
        result.value = null
        val status = SecItemCopyMatching(query, result.ptr)
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Could not read protected security key ($status)" }
        val data = requireNotNull(result.value) as CFDataRef
        try {
            check(CFDataGetLength(data) == 32L) { "Invalid protected security key" }
            requireNotNull(CFDataGetBytePtr(data)).readBytes(32)
        } finally { CFRelease(data) }
    } }

    private fun <T> withQuery(block: (CFMutableDictionaryRef) -> T): T = memScoped {
        val query = requireNotNull(CFDictionaryCreateMutable(null, 0, null, null))
        val service = requireNotNull(CFStringCreateWithCString(null, serviceName, kCFStringEncodingUTF8))
        val account = requireNotNull(CFStringCreateWithCString(null, "security-root-v1", kCFStringEncodingUTF8))
        val group = accessGroup?.let { requireNotNull(CFStringCreateWithCString(null, it, kCFStringEncodingUTF8)) }
        try {
            CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionarySetValue(query, kSecAttrService, service)
            CFDictionarySetValue(query, kSecAttrAccount, account)
            CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
            if (group != null) CFDictionarySetValue(query, kSecAttrAccessGroup, group)
            block(query)
        } finally {
            CFRelease(query)
            if (group != null) CFRelease(group)
            CFRelease(account)
            CFRelease(service)
        }
    }
}

