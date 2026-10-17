package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import kotlinx.serialization.json.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class KarakumBindings : ProcessTask() {
    @get:Input abstract val configuration: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val configurationFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryFile: RegularFileProperty
    init {
        configurationFile.convention(workingDirectory.file(configuration))
        entryFile.convention(project.layout.file(configurationFile.map { config ->
            val input = Json.parseToJsonElement(config.asFile.readText()).jsonObject.getValue("input").jsonPrimitive.content
            config.asFile.parentFile.resolve(input)
        }))
    }
    override fun commandArguments() = listOf("exec", "node", "--input-type=module", "-e",
        "import{readFileSync}from'node:fs';import{createRequire}from'node:module';import{pathToFileURL}from'node:url';const r=createRequire(process.cwd()+'/package.json');const{generate}=await import(pathToFileURL(r.resolve('karakum')));const ts=r('typescript');const config=JSON.parse(readFileSync(process.argv[1],'utf8'));config.plugins=[{async setup(){},async traverse(){},async render(n){return n.kind===ts.SyntaxKind.VoidKeyword&&ts.isTypeReferenceNode(n.parent)?'js.core.Void':null},async generate(){return[]}}];await generate(config);",
        configuration.get())
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.KarakumBindings(executionSpec(), configuration.get())
}
