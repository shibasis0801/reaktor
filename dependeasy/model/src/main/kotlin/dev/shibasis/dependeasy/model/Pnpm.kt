package dev.shibasis.dependeasy.model

object Pnpm {
    fun typeScript(adapter: String, projects: List<String>, emit: Boolean) =
        listOf("exec", "node", adapter, if (emit) "--project" else "--projects") + projects

    fun vite(configuration: String?) = listOf("exec", "vite", "build") +
        configuration?.let { listOf("--config", it) }.orEmpty()

    fun node(script: String, arguments: List<String>) = listOf(script) + arguments

    fun repair(arguments: List<String>) = arguments + "--force"

    fun bindings(configuration: String) = listOf("exec", "node", "--input-type=module", "-e",
        "import{readFileSync}from'node:fs';import{resolve}from'node:path';import{createRequire}from'node:module';import{pathToFileURL}from'node:url';const r=createRequire(process.cwd()+'/package.json');const{generate}=await import(pathToFileURL(r.resolve('karakum')));const ts=r('typescript');const config=JSON.parse(readFileSync(process.argv[1],'utf8'));if(config.ignoreOutput)config.ignoreOutput=(Array.isArray(config.ignoreOutput)?config.ignoreOutput:[config.ignoreOutput]).map(p=>resolve(config.cwd??'.',p));config.plugins=[{async setup(){},async traverse(){},async render(n){return n.kind===ts.SyntaxKind.VoidKeyword&&ts.isTypeReferenceNode(n.parent)?'js.core.Void':null},async generate(){return[]}}];await generate(config);",
        configuration)
}
