package dev.shibasis.reaktor.cloud.google

import dev.shibasis.reaktor.cloud.http.ServiceTokenRequest

interface PubSubProject {
    val project: String
    val credentialFile: String
    val observations: Map<String, String>
    val observationAuthority: ServiceTokenRequest?
}
