package dev.shibasis.reaktor.cloud.kubernetes

interface ClusterService {
    val kubeconfig: String
    val namespace: String
    val service: String
    val port: Int
}
