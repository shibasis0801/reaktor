package dev.shibasis.reaktor.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.shibasis.reaktor.auth.db.AuthSecretStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credentials persist as ciphertext; the AES key stays in Android Keystore. */
class AndroidAuthSecretStore(context: Context) : AuthSecretStore {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("reaktor_auth_secrets", Context.MODE_PRIVATE)
    private val alias = "${app.packageName}.reaktor.auth.v1"
    private val aad = alias.toByteArray(Charsets.UTF_8)

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val encoded = preferences.getString("session", null) ?: return@withContext null
        val pieces = encoded.split(':')
        require(pieces.size == 3 && pieces[0] == "v1") { "Unsupported protected auth record" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(create = false),
            GCMParameterSpec(128, Base64.decode(pieces[1], Base64.NO_WRAP)))
        cipher.updateAAD(aad)
        cipher.doFinal(Base64.decode(pieces[2], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }

    override suspend fun write(value: String): Unit = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val encoded = "v1:${Base64.encodeToString(cipher.iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
        check(preferences.edit().putString("session", encoded).commit()) { "Could not persist protected auth session" }
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Protected auth session key is unavailable; sign in again" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }
}
