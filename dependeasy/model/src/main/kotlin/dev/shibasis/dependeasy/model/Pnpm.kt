package dev.shibasis.dependeasy.model

object Pnpm {
    fun installStandalone() = listOf("install", "--frozen-lockfile", "--ignore-workspace")

    fun typeScript(adapter: String, projects: List<String>, emit: Boolean) =
        listOf("exec", "node", adapter, if (emit) "--project" else "--projects") + projects

    fun vite(configuration: String?) = listOf("exec", "vite", "build") +
        configuration?.let { listOf("--config", it) }.orEmpty()

    fun node(script: String, arguments: List<String>) = listOf(script) + arguments

    fun vitest() = listOf("exec", "vitest", "run")

    fun tests(paths: List<String>) = listOf("exec", "node", "--test") + paths

    fun playwright(suite: Playwright) = listOf("exec", "playwright", "test", "--config", suite.configuration) +
        suite.files + (if (suite.headed) listOf("--headed") else emptyList()) +
        suite.workers?.let { listOf("--workers", it.toString()) }.orEmpty()

    fun lighthouse(audit: Lighthouse, url: String, executable: String): List<String> {
        val command = listOf("exec", "reaktor-lighthouse", "--target", audit.target, "--url", url,
            "--preset", audit.profile.name.lowercase(), "--runs", audit.runs.toString(), "--assert", "--out", audit.report)
        return when (val page = audit.page) {
            is Lighthouse.Page.Remote -> command
            is Lighthouse.Page.Bundle -> listOf("exec", "node", page.server, page.directory,
                java.net.URI(url).port.toString(), executable) + command
        }
    }

    fun repair(arguments: List<String>) = arguments + "--force"

    fun bindings(configuration: String) = listOf("exec", "node", "--input-type=module", "-e",
        "import{readFileSync}from'node:fs';import{resolve}from'node:path';import{createRequire}from'node:module';import{pathToFileURL}from'node:url';const r=createRequire(process.cwd()+'/package.json');const{generate}=await import(pathToFileURL(r.resolve('karakum')));const ts=r('typescript');const config=JSON.parse(readFileSync(process.argv[1],'utf8'));if(config.ignoreOutput)config.ignoreOutput=(Array.isArray(config.ignoreOutput)?config.ignoreOutput:[config.ignoreOutput]).map(p=>resolve(config.cwd??'.',p));config.plugins=[{async setup(){},async traverse(){},async render(n){return n.kind===ts.SyntaxKind.VoidKeyword&&ts.isTypeReferenceNode(n.parent)?'js.core.Void':null},async generate(){return[]}}];await generate(config);",
        configuration)
}
