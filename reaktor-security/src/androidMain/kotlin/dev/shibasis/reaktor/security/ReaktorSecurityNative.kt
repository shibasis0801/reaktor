@file:Suppress("KotlinJniMissingFunction")

package dev.shibasis.reaktor.security

object ReaktorSecurityNative {
    init {
        System.loadLibrary("ReaktorSecurity")
    }

    external fun statusOk(): Int
    external fun create(tenant: ByteArray, app: ByteArray, ids: Array<ByteArray>, keys: Array<ByteArray>, suite: Int, snapshot: ByteArray?): Long
    external fun keyRing(tenant: ByteArray, app: ByteArray, ids: Array<ByteArray>, keys: Array<ByteArray>, encoded: ByteArray): Long
    external fun destroy(handle: Long, ring: Boolean)
    external fun bytes(handle: Long, operation: Int, id: ByteArray, argument: ByteArray): ByteArray
    external fun action(handle: Long, operation: Int, id: ByteArray, argument: ByteArray): Long
    external fun members(handle: Long, id: ByteArray, users: Array<ByteArray>)
    external fun roster(handle: Long, id: ByteArray): Array<ByteArray>
    external fun prepare(handle: Long, operation: Int, id: ByteArray, packages: Array<ByteArray>, index: Int, info: ByteArray): Array<ByteArray>
    external fun seal(handle: Long, id: ByteArray, envelope: ByteArray, plaintext: ByteArray, circle: Boolean, anonymous: Boolean): Array<ByteArray>
    external fun open(handle: Long, ring: Boolean, id: ByteArray, envelope: ByteArray, header: ByteArray, commitment: ByteArray, ciphertext: ByteArray, circle: Boolean): Array<ByteArray>
    external fun storage(operation: Int, key: ByteArray, aad: ByteArray, input: ByteArray): ByteArray
}
