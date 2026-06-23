package dev.shibasis.reaktor.secrets

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import kotlinx.serialization.Serializable

@Serializable
data class SecretRef(
    val projectId: String,
    val secretId: String,
    val version: String = LATEST_VERSION,
) {
    val resourceName: String
        get() = "projects/$projectId/secrets/$secretId/versions/$version"

    val secretName: String
        get() = "projects/$projectId/secrets/$secretId"

    fun version(version: String): SecretRef = copy(version = version)

    companion object {
        const val LATEST_VERSION = "latest"

        fun gcp(
            projectId: String,
            secretId: String,
            version: String = LATEST_VERSION,
        ): SecretRef =
            SecretRef(
                projectId = projectId.requireSecretPart("projectId"),
                secretId = secretId.requireSecretPart("secretId"),
                version = version.requireSecretPart("version"),
            )

        fun parse(resourceName: String): SecretRef {
            val parts = resourceName.split("/")
            require(parts.size == 6) { "Secret resource must be projects/{project}/secrets/{secret}/versions/{version}: $resourceName" }
            require(parts[0] == "projects" && parts[2] == "secrets" && parts[4] == "versions") {
                "Secret resource must be projects/{project}/secrets/{secret}/versions/{version}: $resourceName"
            }
            return gcp(parts[1], parts[3], parts[5])
        }
    }
}

@Serializable
data class SecretValue(
    val ref: SecretRef,
    val value: String,
    val versionName: String = ref.resourceName,
    val etag: String? = null,
)

@Serializable
data class SecretVersion(
    val ref: SecretRef,
    val versionName: String,
    val etag: String? = null,
)

interface SecretStore {
    val defaultProjectId: String?
        get() = null

    fun ref(
        secretId: String,
        projectId: String? = null,
        version: String = SecretRef.LATEST_VERSION,
    ): SecretRef =
        SecretRef.gcp(
            projectId = projectId ?: defaultProjectId ?: error("No GCP projectId was provided and this SecretStore has no defaultProjectId"),
            secretId = secretId,
            version = version,
        )

    suspend fun get(ref: SecretRef): Result<SecretValue>

    suspend fun getString(ref: SecretRef): Result<String> =
        get(ref).map(SecretValue::value)

    suspend fun getString(
        secretId: String,
        projectId: String? = null,
        version: String = SecretRef.LATEST_VERSION,
    ): Result<String> =
        getString(ref(secretId, projectId, version))

    suspend fun requireString(ref: SecretRef): String =
        getString(ref).getOrThrow()

    suspend fun requireString(
        secretId: String,
        projectId: String? = null,
        version: String = SecretRef.LATEST_VERSION,
    ): String =
        getString(secretId, projectId, version).getOrThrow()

    suspend fun addVersion(
        ref: SecretRef,
        value: String,
    ): Result<SecretVersion> =
        Result.failure(UnsupportedOperationException("Secret writes are not implemented by ${this::class.simpleName}"))

    suspend fun addVersion(
        secretId: String,
        value: String,
        projectId: String? = null,
    ): Result<SecretVersion> =
        addVersion(ref(secretId, projectId), value)
}

abstract class SecretStoreAdapter<Controller>(
    controller: Controller,
) : Adapter<Controller>(controller), SecretStore

class InMemorySecretStore(
    override val defaultProjectId: String? = null,
    initial: Map<SecretRef, String> = emptyMap(),
) : SecretStore {
    private val values = initial.toMutableMap()
    private val versions = mutableMapOf<SecretRef, Int>()

    override suspend fun get(ref: SecretRef): Result<SecretValue> =
        runCatching {
            val resolved = resolveLatest(ref)
            val value = values[resolved] ?: error("Missing secret ${ref.resourceName}")
            SecretValue(
                ref = ref,
                value = value,
                versionName = resolved.resourceName,
            )
        }

    override suspend fun addVersion(
        ref: SecretRef,
        value: String,
    ): Result<SecretVersion> =
        runCatching {
            val next = versions.getOrElse(ref.copy(version = SecretRef.LATEST_VERSION)) { 0 } + 1
            val latestKey = ref.copy(version = SecretRef.LATEST_VERSION)
            val versionedRef = ref.copy(version = next.toString())
            versions[latestKey] = next
            values[versionedRef] = value
            values[latestKey] = value
            SecretVersion(
                ref = versionedRef,
                versionName = versionedRef.resourceName,
            )
        }

    private fun resolveLatest(ref: SecretRef): SecretRef {
        if (ref.version != SecretRef.LATEST_VERSION) return ref
        return versions[ref]?.let { ref.copy(version = it.toString()) } ?: ref
    }
}

class UnsupportedSecretStore(
    override val defaultProjectId: String? = null,
    private val message: String,
) : SecretStore {
    override suspend fun get(ref: SecretRef): Result<SecretValue> =
        Result.failure(UnsupportedOperationException(message))
}

var Feature.Secrets by CreateSlot<SecretStore>()

fun requireSecretStore(): SecretStore =
    Feature.Secrets ?: error("Feature.Secrets is not configured")

suspend fun SecretRef.secretValue(
    store: SecretStore = requireSecretStore(),
): Result<SecretValue> =
    store.get(this)

suspend fun SecretRef.secretString(
    store: SecretStore = requireSecretStore(),
): Result<String> =
    store.getString(this)

private fun String.requireSecretPart(name: String): String {
    require(isNotBlank()) { "Secret $name must not be blank" }
    require('/' !in this) { "Secret $name must not contain '/': $this" }
    return this
}
