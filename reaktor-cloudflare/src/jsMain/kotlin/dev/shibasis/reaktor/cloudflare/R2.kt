@file:Suppress("INTERFACE_WITH_SUPERCLASS", "unused", "PropertyName")

package dev.shibasis.reaktor.cloudflare

import js.core.ReadonlyArray
import js.core.ReadonlyRecord
import kotlin.js.ArrayBuffer
import kotlin.js.Date
import kotlin.js.Promise
import org.w3c.fetch.Headers
import org.w3c.files.Blob
import web.streams.ReadableStream

/** Error returned by R2 helpers. */
external interface R2Error : kotlin.js.Error {
    val code: Int
    val action: String
}

/** Pagination options passed to [R2Bucket.list]. */
external interface R2ListOptions {
    var limit: Int?
    var prefix: String?
    var cursor: String?
    var delimiter: String?
    var startAfter: String?
}

/** Cloudflare R2 object storage bucket binding. */
typealias R2PutValue = Any /* ReadableStream<*> | ArrayBuffer | ArrayBufferView | String | Blob | Nothing? */

typealias R2ConditionalInput = Any /* R2Conditional | Headers */

typealias R2RangeInput = Any /* R2Range | Headers */

external open class R2Bucket {
    fun head(key: String): Promise<R2Object?>
    fun get(key: String, options: R2GetOptionsWithConditions): Promise<R2Object?>
    fun get(key: String, options: R2GetOptions = definedExternally): Promise<R2ObjectBody?>
    fun put(key: String, value: R2PutValue, options: R2PutOptionsWithConditions): Promise<R2Object?>
    fun put(key: String, value: R2PutValue, options: R2PutOptions = definedExternally): Promise<R2Object>
    fun createMultipartUpload(key: String, options: R2MultipartOptions = definedExternally): Promise<R2MultipartUpload>
    fun resumeMultipartUpload(key: String, uploadId: String): R2MultipartUpload
    fun delete(keys: String): Promise<Unit>
    fun delete(keys: ReadonlyArray<String>): Promise<Unit>
    fun list(options: R2ListOptions = definedExternally): Promise<R2Objects>
}

/** Multipart upload session. */
external interface R2MultipartUpload {
    val key: String
    val uploadId: String
    fun uploadPart(partNumber: Int, value: Any?): Promise<R2UploadedPart>
    fun abort(): Promise<Unit>
    fun complete(uploadedParts: ReadonlyArray<R2UploadedPart>): Promise<R2Object>
}

/** Descriptor of an uploaded multipart chunk. */
external interface R2UploadedPart {
    var partNumber: Int
    var etag: String
}

/** Metadata describing an R2 object. */
external open class R2Object {
    val key: String
    val version: String
    val size: Double
    val etag: String
    val httpEtag: String
    val checksums: R2Checksums
    val uploaded: Date
    val httpMetadata: R2HTTPMetadata?
    val customMetadata: ReadonlyRecord<String, String>?
    val range: R2Range?
    val storageClass: String
    fun writeHttpMetadata(headers: Headers)
}

/** R2 object with an attached body. */
external open class R2ObjectBody : R2Object() {
    val body: ReadableStream<Any?>
    val bodyUsed: Boolean
    fun arrayBuffer(): Promise<ArrayBuffer>
    fun text(): Promise<String>
    fun <T> json(): Promise<T>
    fun blob(): Promise<Blob>
}

/** Range selection supplied when retrieving objects. */
typealias R2Range = Any /* Cloudflare union type */

/** Conditional request options. */
external interface R2Conditional {
    var etagMatches: String?
    var etagDoesNotMatch: String?
    var uploadedBefore: Date?
    var uploadedAfter: Date?
    var secondsGranularity: Boolean?
}

/** Options supplied to [R2Bucket.get]. */
external interface R2GetOptions {
    var onlyIf: R2ConditionalInput?
    var range: R2RangeInput?
}

/** Options supplied to [R2Bucket.put]. */
external interface R2PutOptions {
    var onlyIf: R2ConditionalInput?
    var httpMetadata: R2HTTPMetadata?
    var customMetadata: ReadonlyRecord<String, String>?
    var md5: Any?
    var sha1: Any?
    var sha256: Any?
    var sha384: Any?
    var sha512: Any?
    var storageClass: String?
}

/** Options used when creating multipart uploads. */
external interface R2MultipartOptions {
    var httpMetadata: R2HTTPMetadata?
    var customMetadata: ReadonlyRecord<String, String>?
    var storageClass: String?
}

/** Strongly typed [R2Bucket.get] options that include a mandatory condition block. */
external interface R2GetOptionsWithConditions : R2GetOptions {
    override var onlyIf: R2ConditionalInput
}

/** Strongly typed [R2Bucket.put] options that include a mandatory condition block. */
external interface R2PutOptionsWithConditions : R2PutOptions {
    override var onlyIf: R2ConditionalInput
}

/** Object checksum metadata. */
external interface R2Checksums {
    val md5: ArrayBuffer?
    val sha1: ArrayBuffer?
    val sha256: ArrayBuffer?
    val sha384: ArrayBuffer?
    val sha512: ArrayBuffer?
    fun toJSON(): R2StringChecksums
}

/** String based representation of an object's checksums. */
external interface R2StringChecksums {
    var md5: String?
    var sha1: String?
    var sha256: String?
    var sha384: String?
    var sha512: String?
}

/** HTTP metadata stored alongside an R2 object. */
external interface R2HTTPMetadata {
    var contentType: String?
    var contentLanguage: String?
    var contentDisposition: String?
    var contentEncoding: String?
    var cacheControl: String?
    var cacheExpiry: Date?
}

/** List response returned by [R2Bucket.list]. */
external interface R2Objects {
    val objects: ReadonlyArray<R2Object>
    val delimitedPrefixes: ReadonlyArray<String>
    val truncated: Boolean
    val cursor: String?
}
