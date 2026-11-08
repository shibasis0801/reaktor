package dev.shibasis.reaktor.cloud.api

import dev.shibasis.reaktor.cloud.http.CredentialName
import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class GrafanaWake(val path: String, val token: CredentialName)

@Serializable
data class GrafanaSite(val url: String, val user: String = "admin", val password: CredentialName? = null, val token: CredentialName? = null,
    val wake: GrafanaWake? = null)

@Serializable
data class KubernetesSite(val kubeconfig: String)

@Serializable
data class DockerSite(val context: String? = null)

@Serializable
data class CloudDeclarations(val grafana: GrafanaSite? = null, val kubernetes: KubernetesSite? = null, val docker: DockerSite? = null) {
    companion object {
        const val Path = ".reaktor/cloud.json"

        fun of(workspace: File): CloudDeclarations = File(workspace, Path).takeIf { it.isFile }
            ?.let { lenientJson.decodeFromString(serializer(), it.readText()) } ?: CloudDeclarations()
    }
}
