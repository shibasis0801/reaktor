@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.work.Work
import dev.shibasis.reaktor.work.definition.Durable
import dev.shibasis.reaktor.work.definition.input
import dev.shibasis.reaktor.work.definition.signal
import dev.shibasis.reaktor.work.definition.step
import dev.shibasis.reaktor.work.definition.timer
import dev.shibasis.reaktor.work.execution.WorkScheduler
import dev.shibasis.reaktor.work.host.cloudflare.CloudflareWorkflowHost
import dev.shibasis.reaktor.work.run.RunRecord
import dev.shibasis.reaktor.work.run.RunState
import dev.shibasis.reaktor.work.run.StepState
import dev.shibasis.reaktor.work.run.WorkScope
import dev.shibasis.reaktor.work.store.ObjectWorkStore
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.promise
import org.koin.dsl.koinApplication
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@JsModule("node:fs") private external object ProofFs {
    fun mkdtempSync(prefix: String): String
    fun rmSync(path: String, options: dynamic)
    fun readFileSync(path: String, encoding: String): String
    fun writeFileSync(path: String, data: String)
    fun existsSync(path: String): Boolean
}
@JsModule("node:os") private external object ProofOs { fun tmpdir(): String }
@JsModule("node:module") private external object ProofModule { fun createRequire(path: String): dynamic }
@JsModule("node:process") private external object ProofProcess { fun cwd(): String }

internal class Chores(graph: Graph, private val executed: suspend (String) -> Unit) :
    Workflow(graph, "proof.chores", "Chores"), Durable<String, String> {
    override val requirement = AuthRequirement().forServices()
    override val input by input<String>()
    val fetch by step(input, effect = Effect.ReadOnly) { name ->
        executed("fetch#$attempt")
        if (attempt < 3) error("upstream blip") else "list for $name"
    }
    val rest by timer(2.seconds)
    val approval by signal<String>()
    val finish by step(fetch, rest, approval, effect = Effect.ReadOnly) { list, _, by ->
        executed("finish#$attempt")
        "$list, approved by $by"
    }
    override val output = finish
}

private val ProofAuthority = AuthContext(principal = PrincipalRef("workflows-proof", PrincipalKind.SERVICE), appId = "proof",
    audience = "proof", method = AuthMethod.SERVICE_CREDENTIAL)

private class Proof(env: dynamic) {
    private val koin = koinApplication {}
    private val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
    private val database = D1Database(env.DB.unsafeCast<RawD1Database>())
    val chores = graph.Node { Chores(it) { name -> database.execute("INSERT INTO executions (name) VALUES (%V)", name) } }
    val work = graph.Node { Work(it, WorkScope.from("proof", ProofAuthority), ObjectWorkStore(D1ObjectDatabase(database, "work", ObjectWorkStore.JOURNAL_PREFIX)),
        WorkScheduler { _, _ -> }, { ProofAuthority }, "workers") }
    val host = CloudflareWorkflowHost(work, env.CHORES, GlobalScope)
}

private var proof: Proof? = null
private fun proof(env: dynamic): Proof = proof ?: Proof(env).also { proof = it }

@JsExport
fun proofRun(env: dynamic, event: dynamic, step: dynamic): Promise<String> = proof(env).host.run(event, step)

@JsExport
fun proofFetch(request: dynamic, env: dynamic): Promise<dynamic> = GlobalScope.promise {
    val proof = proof(env)
    val url: dynamic = js("new URL(request.url)")
    val key = url.searchParams.get("key") as String
    val body = try {
        when (url.pathname as String) {
            "/start" -> proof.host.start(proof.chores, url.searchParams.get("name") as String, key).id
            "/approve" -> proof.host.signal(key, proof.chores.approval, url.searchParams.get("by") as String).let { "approved" }
            else -> proof.work.run(proof.chores, key).record()?.let { json.encodeToString(RunRecord.serializer(), it) } ?: "null"
        }
    } catch (failure: Throwable) {
        "failed: ${failure.message}"
    }
    js("new Response(body)")
}

class CloudflareWorkflowsTest {
    private fun worker(directory: String) {
        val compiled = "${ProofProcess.cwd()}/kotlin/reaktor-reaktor-cloudflare-test.mjs"
        check(ProofFs.existsSync(compiled)) { "The compiled test module is not at $compiled" }
        val joda = ProofModule.createRequire("${ProofProcess.cwd()}/package.json").resolve("@js-joda/core") as String
        ProofFs.writeFileSync("$directory/js-joda.mjs", ProofFs.readFileSync(joda.substringBeforeLast('/') + "/js-joda.esm.js", "utf8"))
        var stubs = 0
        val bundled = BareImport.replace(ProofFs.readFileSync(compiled, "utf8")) { match ->
            val (clause, specifier) = match.destructured
            when (specifier) {
                "cloudflare:workers" -> match.value
                "@js-joda/core" -> "import $clause from './js-joda.mjs';"
                else -> {
                    val stub = "stub-${stubs++}.mjs"
                    val names = if (clause.startsWith("*")) emptyList()
                    else clause.trim('{', '}', ' ').split(',').map { it.trim().substringBefore(' ') }
                    ProofFs.writeFileSync("$directory/$stub", names.joinToString("\n", postfix = "\nexport {};\n") {
                        "export function $it() { throw new Error('$specifier is not available inside workerd'); }"
                    })
                    "import $clause from './$stub';"
                }
            }
        }
        ProofFs.writeFileSync("$directory/test.mjs", DynamicImport.replace(bundled) { "Promise.reject(new Error('${it.groupValues[1]} is not available inside workerd'))" })
        ProofFs.writeFileSync("$directory/quiet.mjs", "globalThis.describe = () => {}; globalThis.it = () => {}; globalThis.xdescribe = () => {}; globalThis.xit = () => {};\n")
        ProofFs.writeFileSync("$directory/worker.mjs", """
            import "./quiet.mjs";
            import { WorkflowEntrypoint } from "cloudflare:workers";
            import { proofRun, proofFetch } from "./test.mjs";
            export class ChoresWorkflow extends WorkflowEntrypoint {
                run(event, step) { return proofRun(this.env, event, step); }
            }
            export default { fetch(request, env) { return proofFetch(request, env); } };
        """.trimIndent())
    }

    private fun start(directory: String): Miniflare {
        val options: dynamic = js("({})")
        options.modules = true
        options.modulesRoot = directory
        options.scriptPath = "$directory/worker.mjs"
        options.modulesRules = js("[{ type: 'ESModule', include: ['**/*.mjs'] }]")
        options.compatibilityDate = "2026-05-26"
        options.compatibilityFlags = arrayOf("nodejs_compat")
        options.d1Databases = js("({ DB: 'workflows-proof' })")
        options.d1Persist = "$directory/d1"
        options.workflows = js("({ CHORES: { name: 'chores', className: 'ChoresWorkflow' } })")
        options.workflowsPersist = "$directory/workflows"
        return Miniflare(options)
    }

    private suspend fun call(host: Miniflare, path: String): String {
        val response: dynamic = host.asDynamic().dispatchFetch("http://proof$path").unsafeCast<Promise<dynamic>>().await()
        return response.text().unsafeCast<Promise<String>>().await()
    }

    private suspend fun until(host: Miniflare, key: String, done: (RunRecord) -> Boolean): RunRecord {
        var body = "null"
        repeat(80) {
            body = call(host, "/run?key=$key")
            check(!body.startsWith("failed:")) { body }
            if (body != "null") json.decodeFromString(RunRecord.serializer(), body).takeIf(done)?.let { return it }
            delay(250)
        }
        error("Run '$key' did not reach the expected state: $body")
    }

    @Test fun anInstanceRetriesSleepsWaitsForASignalAndResumesAfterARestart() = GlobalScope.promise {
        val directory = ProofFs.mkdtempSync("${ProofOs.tmpdir()}/reaktor-workflows-")
        worker(directory)
        var host = start(directory)
        try {
            D1Database(host.getD1Database("DB").await()).execute("CREATE TABLE executions (name TEXT NOT NULL)")
            assertEquals("chores-1", call(host, "/start?key=chores-1&name=ada"))
            val retried = until(host, "chores-1") { it.step("fetch").state == StepState.Succeeded && it.step("rest").state == StepState.Succeeded }
            assertEquals(3, retried.step("fetch").attempt)
            assertEquals(StepState.Waiting, retried.step("approval").state)
            host.dispose().await()
            host = start(directory)
            assertEquals("approved", call(host, "/approve?key=chores-1&by=bea"))
            val finished = until(host, "chores-1") { it.state.finished }
            assertEquals(RunState.Succeeded, finished.state)
            assertEquals("\"list for ada, approved by bea\"", finished.step("finish").result)
            val executions = D1Database(host.getD1Database("DB").await()).rawRows("SELECT name FROM executions ORDER BY rowid").map { it.requireString("name") }
            assertEquals(listOf("fetch#1", "fetch#2", "fetch#3", "finish#1"), executions)
            val rest = finished.step("rest")
            println("reaktor-work wake latency on workerd: ${checkNotNull(rest.endedAtMillis) - checkNotNull(rest.startedAtMillis) - 2_000} ms after the timer was due")
        } finally {
            host.dispose().await()
            ProofFs.rmSync(directory, js("({recursive: true, force: true})"))
        }
    }
}

private val BareImport = Regex("""^import (.+) from '([^.'][^']*)';$""", RegexOption.MULTILINE)
private val DynamicImport = Regex("""import\('([^.'][^']*)'\)""")
