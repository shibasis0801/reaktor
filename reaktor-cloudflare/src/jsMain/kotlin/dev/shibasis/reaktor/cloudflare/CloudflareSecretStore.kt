package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.secrets.SecretRef
import dev.shibasis.reaktor.secrets.SecretStoreAdapter
import dev.shibasis.reaktor.secrets.SecretValue

class CloudflareSecretStore(
    private val context: CloudflareContext,
    override val defaultProjectId: String? = null,
) : SecretStoreAdapter<CloudflareContext>(context) {
    override suspend fun get(ref: SecretRef): Result<SecretValue> =
        runCatching {
            val value = context.secretOrNull(ref.secretId)
                ?: error("Missing Cloudflare secret binding '${ref.secretId}'")
            SecretValue(
                ref = ref,
                value = value,
                versionName = ref.resourceName,
            )
        }
}

fun CloudflareContext.boundSecretStore(
    defaultProjectId: String? = null,
): CloudflareSecretStore =
    CloudflareSecretStore(this, defaultProjectId)
