package dev.shibasis.reaktor.db.adapters

import dev.shibasis.reaktor.db.adapters.SqlAdapter
import dev.shibasis.reaktor.io.adapters.FileAdapter
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.ByteArrayContent
import kotlin.js.JsExport

@JsExport
class SyncAdapter(
    private val client: HttpClient,
    private val sqlAdapter: SqlAdapter<*>,
    private val fileAdapter: FileAdapter<*>
) {
    suspend fun upload(uploadUrl: String, snapshotName: String = "snapshot.db") {
        sqlAdapter.backup(snapshotName)
        val snapshotPath = fileAdapter.resolvePath(snapshotName)

        val bytes = fileAdapter.readBinaryFile(snapshotPath)
            ?: throw Error("Failed to read snapshot file at $snapshotPath")

        try {
            val response = client.put(uploadUrl) {
                contentType(ContentType.Application.OctetStream)
                setBody(ByteArrayContent(bytes))
            }

            if (response.status.value !in 200..299) {
                throw Error("Upload failed with status: ${response.status}")
            }
        } finally {
            fileAdapter.delete(snapshotPath)
        }
    }

    suspend fun download(downloadUrl: String, restoreName: String = "restore_temp.db") {
        val restorePath = fileAdapter.resolvePath(restoreName)

        try {
            val bytes = client.get(downloadUrl).body<ByteArray>()
            fileAdapter.writeBinaryFile(restorePath, bytes)
            sqlAdapter.restore(restoreName)
        } catch (e: Throwable) {
            fileAdapter.delete(restorePath)
            throw e
        }
    }
}