package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.io.adapters.FileAdapter

class CloudflareR2FileAdapter(
    bucket: R2Bucket,
    override val cacheDirectory: String = "cache",
    override val documentDirectory: String = "documents",
) : FileAdapter<R2Bucket>(bucket) {
    override suspend fun exists(path: String): Boolean =
        requireBucket().head(normalize(path)) != null

    override suspend fun delete(path: String) {
        requireBucket().delete(normalize(path))
    }

    override suspend fun copy(
        sourcePath: String,
        destPath: String,
    ) {
        val bytes = readBinaryFile(sourcePath) ?: return
        writeBinaryFile(destPath, bytes)
    }

    override suspend fun readBinaryFile(path: String): ByteArray? =
        requireBucket().getBytes(normalize(path))

    override suspend fun readTextFile(path: String): String? =
        requireBucket().getText(normalize(path))

    override suspend fun writeTextFile(
        path: String,
        data: String,
    ) {
        requireBucket().putText(normalize(path), data)
    }

    override suspend fun writeBinaryFile(
        path: String,
        data: ByteArray,
    ) {
        requireBucket().putBytes(normalize(path), data)
    }

    private fun requireBucket(): R2Bucket =
        controller ?: error("R2 bucket controller is unavailable")
}

fun CloudflareContext.file(
    binding: R2Binding,
    cacheDirectory: String = "cache",
    documentDirectory: String = "documents",
): CloudflareR2FileAdapter =
    CloudflareR2FileAdapter(
        bucket = this[binding],
        cacheDirectory = cacheDirectory,
        documentDirectory = documentDirectory,
    )

fun Request.file(
    binding: R2Binding,
    cacheDirectory: String = "cache",
    documentDirectory: String = "documents",
): CloudflareR2FileAdapter =
    context.file(binding, cacheDirectory, documentDirectory)

private fun normalize(path: String): String =
    path.trim().trimStart('/').split('/').filter { it.isNotBlank() }.joinToString("/")
