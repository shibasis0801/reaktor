package dev.shibasis.reaktor.security

import com.sun.jna.*
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ByteByReference

/** Direct Security.framework calls. Passwords never enter a shell command, environment or workspace file. */
class MacKeychainCredentialStore(private val service: String) : CredentialStore {
    init {
        require(System.getProperty("os.name").contains("Mac", ignoreCase = true)) { "macOS Keychain requires macOS" }
        require(service.isNotBlank() && service.length <= 256 && service.none(Char::isISOControl))
    }
    private val native by lazy { KeychainNative() }

    @Synchronized override fun read(key: String): ByteArray? = native.query(service, key) { query ->
        native.set(query, "kSecReturnData", native.cfTrue)
        native.set(query, "kSecMatchLimit", native.symbol("kSecMatchLimitOne"))
        val result = PointerByReference()
        val status = native.security.SecItemCopyMatching(query, result)
        if (status == NOT_FOUND) null else {
            checked(status)
            val data = requireNotNull(result.value) { "Keychain returned no record" }
            try {
                val size = native.cf.CFDataGetLength(data).toLong()
                check(size in 0..MAX_BYTES.toLong()) { "Keychain record exceeds the credential budget" }
                native.cf.CFDataGetBytePtr(data).getByteArray(0, size.toInt())
            } finally { native.cf.CFRelease(data) }
        }
    }

    @Synchronized override fun write(key: String, value: ByteArray) {
        require(value.size in 1..MAX_BYTES) { "Credential record must contain 1–65536 bytes" }
        native.query(service, key) { query ->
            val data = native.cf.CFDataCreate(null, value, NativeLong(value.size.toLong()))
            val changes = native.dictionary()
            try {
                native.set(changes, "kSecValueData", data)
                val updated = native.security.SecItemUpdate(query, changes)
                if (updated == NOT_FOUND) {
                    native.set(query, "kSecValueData", data)
                    val added = native.security.SecItemAdd(query, null)
                    // Another process may have provisioned the same record after the first lookup.
                    if (added == DUPLICATE) {
                        native.cf.CFDictionaryRemoveValue(query, native.symbol("kSecValueData"))
                        checked(native.security.SecItemUpdate(query, changes))
                    } else checked(added)
                } else checked(updated)
            } finally { native.cf.CFRelease(changes); native.cf.CFRelease(data) }
        }
    }

    @Synchronized override fun delete(key: String) = native.query(service, key) { query ->
        val status = native.security.SecItemDelete(query)
        if (status != NOT_FOUND) checked(status)
    }

    private fun checked(status: Int) {
        if (status == -25308 || status == -25293) throw CredentialAccessException("macOS Keychain denied credential access (OSStatus $status). Unlock Keychain and approve Reaktor's access to its credential, then retry sign-in.")
        check(status == 0) { "macOS Keychain operation failed (OSStatus $status)" }
    }
    private companion object { const val NOT_FOUND = -25300; const val DUPLICATE = -25299; const val MAX_BYTES = 65_536 }
}

private interface SecurityFramework : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int
    fun SecItemUpdate(query: Pointer, attributes: Pointer): Int
    fun SecItemDelete(query: Pointer): Int
    fun SecKeychainGetUserInteractionAllowed(state: ByteByReference): Int
    fun SecKeychainSetUserInteractionAllowed(state: Byte): Int
}

private interface CoreFoundationFramework : Library {
    fun CFStringCreateWithCString(allocator: Pointer?, text: String, encoding: Int): Pointer
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong, keyCallbacks: Pointer?, valueCallbacks: Pointer?): Pointer
    fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
    fun CFDictionaryRemoveValue(dictionary: Pointer, key: Pointer)
    fun CFDataCreate(allocator: Pointer?, data: ByteArray, length: NativeLong): Pointer
    fun CFDataGetLength(data: Pointer): NativeLong
    fun CFDataGetBytePtr(data: Pointer): Pointer
    fun CFRelease(value: Pointer)
}

private class KeychainNative {
    private val securityLibrary = NativeLibrary.getInstance("Security")
    private val cfLibrary = NativeLibrary.getInstance("CoreFoundation")
    val security = Native.load("Security", SecurityFramework::class.java)
    val cf = Native.load("CoreFoundation", CoreFoundationFramework::class.java)
    val cfTrue = cfLibrary.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
    fun symbol(name: String): Pointer = securityLibrary.getGlobalVariableAddress(name).getPointer(0)
    fun dictionary(): Pointer = cf.CFDictionaryCreateMutable(null, NativeLong(0),
        cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"), cfLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"))
    fun set(dictionary: Pointer, key: String, value: Pointer) = cf.CFDictionarySetValue(dictionary, symbol(key), value)
    fun <T> query(service: String, key: String, block: (Pointer) -> T): T {
        require(key.isNotBlank() && key.length <= 512 && key.none(Char::isISOControl)) { "Invalid credential record key" }
        val dictionary = dictionary()
        val serviceName = cf.CFStringCreateWithCString(null, service, 0x08000100)
        val account = cf.CFStringCreateWithCString(null, key, 0x08000100)
        try {
            set(dictionary, "kSecClass", symbol("kSecClassGenericPassword"))
            set(dictionary, "kSecAttrService", serviceName)
            set(dictionary, "kSecAttrAccount", account)
            // Background/resume calls must fail closed instead of waiting indefinitely on
            // an invisible OS authentication dialog. This does not grant Keychain access.
            set(dictionary, "kSecUseAuthenticationUI", symbol("kSecUseAuthenticationUIFail"))
            // macOS ignores per-query authentication-UI flags for legacy login-keychain
            // items. Scope and restore its process-level flag under one shared lock.
            return synchronized(interactionLock) {
                val previous = ByteByReference()
                check(security.SecKeychainGetUserInteractionAllowed(previous) == 0) { "Could not inspect Keychain interaction policy" }
                check(security.SecKeychainSetUserInteractionAllowed(0) == 0) { "Could not make Keychain access non-interactive" }
                try { block(dictionary) } finally {
                    check(security.SecKeychainSetUserInteractionAllowed(previous.value) == 0) { "Could not restore Keychain interaction policy" }
                }
            }
        } finally { cf.CFRelease(dictionary); cf.CFRelease(account); cf.CFRelease(serviceName) }
    }
    private companion object { val interactionLock = Any() }
}
