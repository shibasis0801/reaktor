package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.util.UUID
import dev.shibasis.reaktor.tooling.database.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption

class WorkerJvmClient(private val session: InfrastructureSession) {
    private val http = BoundedHttp(session)
    private val json = Json { ignoreUnknownKeys = true }

    fun execute(op: InfrastructureOperation.WorkerCall, environment: Map<String, String>): String {
        require(op.operation.matches(Regex("[a-z0-9.-]{1,100}")))
        require((op.queryFile == null) == (op.resultFile == null))
        val storeRead = op.store?.let { store ->
            op.queryFile?.let { path ->
                val file = File(path)
                checkPrivateFile(file, false)
                require(file.length() in 1..262_144)
                checkPrivateFile(File(requireNotNull(op.resultFile)), true)
                WorkerStoreRead(file.readText(), op.maxRows, op.explain, op.catalog).validate(store)
            } ?: WorkerStoreRead(if (store.provider == "D1") "SELECT 1 AS connected" else if (store.provider == "DurableObjects")
                """{"instance":"endpoint:reaktor-kernel-connectivity"}""" else "{}", 1).validate(store)
        }
        require(op.queryFile == null || storeRead != null)
        val endpoint = URI(op.endpoint)
        require(endpoint.scheme == "https" && endpoint.userInfo == null && endpoint.query == null && endpoint.fragment == null)
        val token = ServiceTokenClient(session).token(op.tokenSource(), environment)
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json", "Content-Type" to "application/json")
        val catalog = json.decodeFromString<WorkerOperationCatalog>(http.request(URI("${op.endpoint.trimEnd('/')}/_reaktor/operations"), headers = headers, maxBytes = 65_536))
        catalog.requireRead(requireNotNull(environment["REAKTOR_ENVIRONMENT"]), op.operation)
        val requestId = UUID.randomUUID().toString()
        val result = json.decodeFromString<WorkerOperationResult>(http.request(
            URI("${op.endpoint.trimEnd('/')}/_reaktor/operations/${op.operation}?requestId=$requestId"),
            body = storeRead?.let { Json.encodeToString(it) }, headers = headers, maxBytes = if (storeRead == null) 1_048_576 else 8_388_608))
        result.requireMatches(catalog, requestId, op.operation)
        if (storeRead != null) {
            val receipt = json.decodeFromJsonElement<QueryReceipt>(result.result)
            receipt.validate(storeRead.maxRows, requireNotNull(op.store).provider)
            val output = Json.encodeToString(receipt.copy(metrics = receipt.metrics + QueryMetric("Worker elapsed", result.durationMillis.toString(), "ms")))
            if (op.resultFile != null) {
                require(output.toByteArray().size <= 8_388_608)
                Files.newByteChannel(File(op.resultFile).toPath(), setOf(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                    val bytes = java.nio.ByteBuffer.wrap(output.toByteArray())
                    while (bytes.hasRemaining()) channel.write(bytes)
                }
                check(receipt.error == null) { "Worker store query failed; diagnostics are in the private session channel" }
                return "Completed Worker store read; result is in the private session channel"
            }
            check(receipt.error == null) { "Worker store read failed" }
            return "Worker ${op.store.provider} binding ${op.store.binding} read succeeded"
        }
        return Json.encodeToString(result)
    }

}
