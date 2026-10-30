package dev.shibasis.reaktor.security

import kotlin.time.Clock

internal fun secretAad(type: SecretRecordType, id: String, aad: ByteArray, sealed: SealedSecret): ByteArray {
    require(id.isNotEmpty() && id.length <= 1024 && aad.size <= 1024 * 1024)
    val fields = listOf("reaktor/secret/v1", sealed.version.toString(), sealed.algorithm, sealed.keyAlias,
        type.name, id, sealed.createdAtEpochMs.toString()).map(String::encodeToByteArray) + listOf(aad)
    require(fields.all { it.size <= 1024 * 1024 })
    val output = ByteArray(fields.sumOf { 4 + it.size })
    var position = 0
    fields.forEach { field ->
        repeat(4) { shift -> output[position++] = (field.size ushr (24 - shift * 8)).toByte() }
        field.copyInto(output, position)
        position += field.size
    }
    return output
}

internal fun sealSecret(key: ByteArray, alias: String, type: SecretRecordType, id: String, plaintext: ByteArray, aad: ByteArray): SealedSecret {
    require(key.size == 32)
    val header = SealedSecret(1, "RSEC-STORAGE-V1", alias, byteArrayOf(), byteArrayOf(), Clock.System.now().toEpochMilliseconds())
    return header.copy(ciphertext = SecurityEngineFactory.sealStorage(key, secretAad(type, id, aad, header), plaintext))
}

internal fun openSecret(key: ByteArray, alias: String, type: SecretRecordType, id: String, sealed: SealedSecret, aad: ByteArray): ByteArray {
    require(key.size == 32 && sealed.version == 1 && sealed.algorithm == "RSEC-STORAGE-V1" && sealed.keyAlias == alias && sealed.nonce.isEmpty())
    return SecurityEngineFactory.openStorage(key, secretAad(type, id, aad, sealed), sealed.ciphertext)
}
