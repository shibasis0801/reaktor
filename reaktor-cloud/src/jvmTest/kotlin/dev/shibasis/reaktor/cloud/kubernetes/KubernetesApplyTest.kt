package dev.shibasis.reaktor.cloud.kubernetes

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.reaktor.cloud.InfrastructureSession
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KubernetesApplyTest {
    private val manifest = """
        apiVersion: v1
        kind: ConfigMap
        metadata:
          name: settings
          namespace: dev
        data:
          mode: fast
        ---
        apiVersion: v1
        kind: Namespace
        metadata:
          name: dev
    """.trimIndent()

    private fun cluster(check: (KubernetesApply, MutableList<String>) -> Unit) {
        val folder = Files.createTempDirectory("kubernetes-apply").toFile()
        val requests = mutableListOf<String>()
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        synchronized(requests) { requests += "${request.method} $path ${request.getHeader("Content-Type").orEmpty()}" }
                        return when {
                            request.getHeader("Authorization") != "Bearer fixture-token" -> MockResponse().setResponseCode(401)
                            path == "/api/v1" -> MockResponse().setBody("""{"resources":[{"name":"configmaps","kind":"ConfigMap","namespaced":true},
                                {"name":"configmaps/status","kind":"ConfigMap","namespaced":true},{"name":"namespaces","kind":"Namespace","namespaced":false}]}""")
                            request.method == "GET" && path == "/api/v1/namespaces/dev/configmaps/settings" ->
                                MockResponse().setBody("""{"kind":"ConfigMap","metadata":{"name":"settings","namespace":"dev","resourceVersion":"41","uid":"u1"},"data":{"mode":"slow"}}""")
                            request.method == "GET" -> MockResponse().setResponseCode(404).setBody("""{"message":"not found"}""")
                            request.method == "PATCH" && path.startsWith("/api/v1/namespaces/dev/configmaps/settings") ->
                                MockResponse().setBody("""{"kind":"ConfigMap","metadata":{"name":"settings","namespace":"dev","resourceVersion":"${if ("dryRun" in path) "41" else "42"}","uid":"u1",
                                    "managedFields":[{"manager":"reaktor"}]},"data":{"mode":"fast"}}""")
                            request.method == "PATCH" && path.startsWith("/api/v1/namespaces/dev") ->
                                MockResponse().setBody("""{"kind":"Namespace","metadata":{"name":"dev","resourceVersion":"7","uid":"n1"}}""")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()
                val kubeconfig = File(folder, "kubeconfig").apply { writeText("""
                    apiVersion: v1
                    kind: Config
                    clusters: [{name: fixture, cluster: {server: "${server.url("/").toString().trimEnd('/')}"}}]
                    users: [{name: fixture, user: {token: fixture-token}}]
                    contexts: [{name: fixture, context: {cluster: fixture, user: fixture}}]
                    current-context: fixture
                """.trimIndent()) }
                InfrastructureSession().use { session -> check(KubernetesApply(KubernetesJvmClient(kubeconfig, session)), requests) }
            }
        } finally {
            folder.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun plansWithAServerSideDryRun() = cluster { apply, requests ->
        val plans = apply.plan(manifest)
        assertEquals(listOf("k8s:dev/ConfigMap/settings", "k8s:Namespace/dev"), plans.map { it.target })
        val settings = plans.first()
        assertEquals(listOf("data.mode"), settings.changes.map { it.path }, "bookkeeping fields stay out of the diff")
        assertEquals(JsonPrimitive("slow"), settings.changes.single().before)
        assertEquals("41", settings.liveVersion)
        assertTrue(requests.any { it.startsWith("PATCH /api/v1/namespaces/dev/configmaps/settings?fieldManager=reaktor&force=true&dryRun=All application/apply-patch+yaml") }, requests.joinToString("\n"))
        assertTrue(plans.last().request.url.endsWith("/api/v1/namespaces/dev?fieldManager=reaktor&force=true"), "a cluster-scoped object has no namespace in its path")
    }

    @Test fun appliesThePlanAndNamesTheNewVersion() = cluster { apply, requests ->
        val plan = apply.plan(manifest).first()
        val receipt = apply.apply(plan, plan.digest)
        assertEquals("42", receipt.remoteVersion)
        assertEquals(1, requests.count { it.startsWith("PATCH /api/v1/namespaces/dev/configmaps/settings?fieldManager=reaktor&force=true ") })
    }
}
