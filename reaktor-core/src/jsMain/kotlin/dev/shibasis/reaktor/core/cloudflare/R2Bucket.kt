@file:Suppress("EXTERNAL_CLASS_CONSTRUCTOR_PROPERTY_PARAMETER")
package dev.shibasis.reaktor.core.cloudflare

import kotlin.js.Date
import kotlin.js.Promise

// Bindings for R2Bucket and related types

external abstract class R2Bucket {
    fun head(key: String): Promise<R2Object?>
    // Overload for get: with an "onlyIf" option
    fun get(key: String, options: R2GetOptions /* expecting onlyIf: R2Conditional | Headers */): Promise<dynamic>
    // Overload for get (default, returning body as R2ObjectBody)
    fun get(key: String, options: R2GetOptions? = definedExternally): Promise<R2ObjectBody?>
    // Overload for put with onlyIf option (returns null if condition not met)
    fun put(
        key: String,
        value: dynamic /* ReadableStream | ArrayBuffer | ArrayBufferView | String | Blob | null */,
        options: R2PutOptions /* expecting onlyIf: R2Conditional | Headers */
    ): Promise<R2Object?>
    // Overload for put (always returns an R2Object)
    fun put(
        key: String,
        value: dynamic /* ReadableStream | ArrayBuffer | ArrayBufferView | String | Blob | null */,
        options: R2PutOptions? = definedExternally
    ): Promise<R2Object>
    fun createMultipartUpload(key: String, options: R2MultipartOptions? = definedExternally): Promise<R2MultipartUpload>
    fun resumeMultipartUpload(key: String, uploadId: String): R2MultipartUpload
    fun delete(keys: dynamic /* String or Array<String> */): Promise<Unit>
    fun list(options: R2ListOptions? = definedExternally): Promise<R2Objects>
}

external interface R2GetOptions {
    var onlyIf: dynamic /* R2Conditional or Headers */?
    var range: dynamic /* R2Range or Headers */?
}

external interface R2PutOptions {
    var onlyIf: dynamic /* R2Conditional or Headers */?
    var httpMetadata: dynamic /* R2HTTPMetadata or Headers */?
    var customMetadata: dynamic /* Record<String, String> */?
    var md5: dynamic /* ArrayBuffer or String */?
    var sha1: dynamic /* ArrayBuffer or String */?
    var sha256: dynamic /* ArrayBuffer or String */?
    var sha384: dynamic /* ArrayBuffer or String */?
    var sha512: dynamic /* ArrayBuffer or String */?
    var storageClass: String?
}

external interface R2MultipartOptions {
    var httpMetadata: dynamic /* R2HTTPMetadata or Headers */?
    var customMetadata: dynamic /* Record<String, String> */?
    var storageClass: String?
}

external interface R2ListOptions {
    var limit: Number?
    var prefix: String?
    var cursor: String?
    var delimiter: String?
    var startAfter: String?
    var include: Array<String>?
}

external abstract class R2MultipartUpload {
    val key: String
    val uploadId: String
    fun uploadPart(
        partNumber: Number,
        value: dynamic /* ReadableStream | ArrayBuffer | ArrayBufferView | String | Blob */
    ): Promise<R2UploadedPart>
    fun abort(): Promise<Unit>
    fun complete(uploadedParts: Array<R2UploadedPart>): Promise<R2Object>
}

external interface R2UploadedPart {
    var partNumber: Number
    var etag: String
}

external abstract class R2Object {
    val key: String
    val version: String
    val size: Number
    val etag: String
    val httpEtag: String
    val checksums: R2Checksums
    val uploaded: Date
    val httpMetadata: R2HTTPMetadata?
    val customMetadata: dynamic /* Record<String, String> */?
    val range: R2Range?
    val storageClass: String
    fun writeHttpMetadata(headers: Headers)
}

external abstract class R2ObjectBody : R2Object {
    val body: ReadableStream
    val bodyUsed: Boolean
    fun arrayBuffer(): Promise<ArrayBuffer>
    fun text(): Promise<String>
    fun <T> json(): Promise<T>
    fun blob(): Promise<Blob>
}

external interface R2HTTPMetadata {
    var contentType: String?
    var contentLanguage: String?
    var contentDisposition: String?
    var contentEncoding: String?
    var cacheControl: String?
    var cacheExpiry: Date?
}

external interface R2Range {
    var offset: Number?
    var length: Number?
    var suffix: Number?
}

external interface R2Conditional {
    var etagMatches: String?
    var etagDoesNotMatch: String?
    var uploadedBefore: Date?
    var uploadedAfter: Date?
    var secondsGranularity: Boolean?
}

external interface R2Checksums {
    val md5: ArrayBuffer?
    val sha1: ArrayBuffer?
    val sha256: ArrayBuffer?
    val sha384: ArrayBuffer?
    val sha512: ArrayBuffer?
    fun toJSON(): R2StringChecksums
}

external interface R2StringChecksums {
    var md5: String?
    var sha1: String?
    var sha256: String?
    var sha384: String?
    var sha512: String?
}

external interface R2Objects {
    val objects: Array<R2Object>
    val delimitedPrefixes: Array<String>
    // When truncated is true a cursor will be provided.
    val truncated: Boolean
    val cursor: String?
}

// Minimal external bindings for types referenced in the definitions
external interface Headers

external interface ReadableStream

external open class ArrayBuffer

external interface Blob