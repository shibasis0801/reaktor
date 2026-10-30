package dev.shibasis.reaktor.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.Clock

class AndroidPlatformSecretBox(private val keyAlias: String) : PlatformSecretBox {
    init { require(keyAlias.isNotBlank() && keyAlias.length <= 256) }

    private fun key(create: Boolean = true): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(keyAlias, null)?.let { return@synchronized it as SecretKey }
        check(create) { "The protected root key is unavailable" }
        fun generate(strongBox: Boolean): SecretKey {
            val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false)
            if (Build.VERSION.SDK_INT >= 28) {
                spec.setUnlockedDeviceRequired(false)
                spec.setIsStrongBoxBacked(strongBox)
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(spec.build())
                generateKey()
            }
        }
        if (Build.VERSION.SDK_INT >= 28) {
            try { generate(true) } catch (_: StrongBoxUnavailableException) { generate(false) }
        } else generate(false)
    }

    override suspend fun seal(recordType: SecretRecordType, recordId: String, plaintext: ByteArray,
        aad: ByteArray, options: SecretAccessOptions): SealedSecret {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        require(plaintext.size <= 64 * 1024 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val header = SealedSecret(1, "AES-256-GCM", keyAlias, cipher.iv, byteArrayOf(), Clock.System.now().toEpochMilliseconds())
        require(header.nonce.size == 12)
        cipher.updateAAD(secretAad(recordType, recordId, aad, header))
        return header.copy(ciphertext = cipher.doFinal(plaintext))
    }

    override suspend fun open(recordType: SecretRecordType, recordId: String, sealed: SealedSecret,
        aad: ByteArray, options: SecretAccessOptions): ByteArray {
        require(!options.requireUserPresence) { "This key is configured for background access" }
        require(sealed.version == 1 && sealed.algorithm == "AES-256-GCM" && sealed.keyAlias == keyAlias)
        require(sealed.nonce.size == 12 && sealed.ciphertext.size in 16..(64 * 1024 * 1024 + 16))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, sealed.nonce))
        }
        cipher.updateAAD(secretAad(recordType, recordId, aad, sealed))
        return cipher.doFinal(sealed.ciphertext)
    }

    private companion object { val keyLock = Any() }
}

