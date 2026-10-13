package dev.shibasis.dependeasy.dagger

import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GenerateDaggerTask : DefaultTask() {
    @get:Input abstract val moduleName: Property<String>
    @get:Input abstract val buildName: Property<String>
    @get:Input abstract val engineVersion: Property<String>
    @get:Input abstract val moduleSource: Property<String>
    @get:Input abstract val browserImage: Property<String>
    @get:Input abstract val gradleProperties: MapProperty<String, String>
    @get:Input abstract val toolchainContract: Property<String>
    @get:Input abstract val declarations: ListProperty<String>
    @get:Input abstract val edges: ListProperty<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val runtimeSource: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputFile abstract val moduleConfiguration: RegularFileProperty

    @TaskAction fun generate() {
        val declared = declarations.get().map(BuildModelReader::decodeNode)
        val dependencies = edges.get().map { BuildModelReader.json.decodeFromString<Edge>(it) }
        var targets = declared.filter { it.host.platform != HostPlatform.Macos && it.host.execution != ExecutionHost.Local }
        do {
            val excluded = declared.filterNot { it in targets }.map { it.id }.toSet()
            val previousSize = targets.size
            targets = targets.filter { target -> dependencies.none { it.from == target.id && it.to in excluded } }
        } while (targets.size != previousSize)
        val methods = targets.map { target ->
            val name = target.id.name
            val recipes = (target.invocation as? Invocation.Command)?.recipes.orEmpty()
            if (recipes.isNotEmpty()) return@map "  @func()\n  async $name(): Promise<string> {\n" +
                "    const outputs = await Promise.all([${recipes.joinToString(", ") { "this.$it()" }}])\n    return outputs.join(\"\\n\")\n  }"
            val output = target.outputs.firstOrNull()?.paths?.firstOrNull()
            val deploy = target.effect == Effect.Deploy
            val environments = JsonArray(target.environments.map(::JsonPrimitive))
            val selectEnvironment = deploy && environments.isNotEmpty()
            val defaultEnvironment = environments.firstOrNull { it.jsonPrimitive.content == "prod" } ?: environments.firstOrNull()
            val environmentParameter = if (selectEnvironment) ", targetEnv: string = $defaultEnvironment" else ""
            val parameters = if (deploy) "cloudflareApiToken: Secret$environmentParameter" else ""
            val result = if (output == null) "stdout()" else "directory(${quote("/work/${buildName.get()}/$output")})"
            val environmentBinding = if (selectEnvironment) ".withEnvVariable(\"TARGET_ENV\", checkedEnvironment(targetEnv, $environments))" else ""
            val secret = if (deploy) ".withSecretVariable(\"CLOUDFLARE_API_TOKEN\", cloudflareApiToken)$environmentBinding" else ""
            val external = target.invocation is Invocation.Command
            val runtime = if (target.runner == "browser") "browserWorkspace(${quote(browserImage.get())})" else if (external) "commandWorkspace()" else "workspace()"
            val commands = if (external) (target.invocation as Invocation.Command).commands.map { JsonArray(it.map(::JsonPrimitive)).toString() }
                else listOf("[\"./gradlew\", ${quote(target.id.project.trimEnd(':') + ":" + target.id.name)}, \"--console=plain\"]")
            "  @func()\n  async $name($parameters): Promise<${if (output == null) "string" else "Directory"}> {\n" +
                "    return (await this.runtime().$runtime)$secret${commands.joinToString("") { ".withExec($it)" }}.$result\n  }"
        }
        val workers = targets.filter { it.worker != null }.associate { requireNotNull(it.worker) to (it.id.project.trimEnd(':') + ":" + it.id.name) }
        val workerEnvironments = targets.filter { it.worker != null }.associate { requireNotNull(it.worker) to JsonArray(it.environments.map(::JsonPrimitive)) }
        val workerMethod = if (workers.isEmpty()) "" else """
            @func()
            async deployWorker(name: string, cloudflareApiToken: Secret, targetEnv: string = "prod"): Promise<string> {
              const targets: Record<string, string> = ${Json.encodeToString(JsonObject.serializer(), JsonObject(workers.mapValues { JsonPrimitive(it.value) }))}
              const environments: Record<string, string[]> = ${JsonObject(workerEnvironments)}
              const target = targets[name]
              if (!target) throw new Error(`Unknown worker '${'$'}{name}'; choose ${'$'}{Object.keys(targets).join(", ")}`)
              return (await this.runtime().workspace()).withSecretVariable("CLOUDFLARE_API_TOKEN", cloudflareApiToken)
                .withEnvVariable("TARGET_ENV", checkedEnvironment(targetEnv, environments[name])).withExec(["./gradlew", target, "--console=plain"]).stdout()
            }
        """.trimIndent()
        val source = """
            // Generated by Gradle generateDagger. Edit Gradle declarations or Dependeasy, then regenerate.
            import { Container, Directory, Secret, argument, object, func } from "@dagger.io/dagger"
            import { BuildRuntime, SOURCE_IGNORES, checkedEnvironment } from "./runtime.js"

            @object()
            export class ${moduleName.get()} {
              source: Directory
              reaktor: Directory
              constructor(@argument({ ignore: SOURCE_IGNORES }) source: Directory, @argument({ ignore: SOURCE_IGNORES }) reaktor: Directory) {
                this.source = source; this.reaktor = reaktor
              }
              private runtime(): BuildRuntime { return new BuildRuntime(this.source, this.reaktor, this.source.file(${quote("${moduleSource.get()}/src/toolchain.json")}), ${quote(buildName.get())}, ${JsonObject(gradleProperties.get().mapKeys { "ORG_GRADLE_PROJECT_${it.key}" }.mapValues { JsonPrimitive(it.value) })}) }
              @func()
              async toolchain(): Promise<Container> { return this.runtime().toolchain() }
              @func()
              async androidToolchain(): Promise<Container> { return this.runtime().androidToolchain() }
              @func()
              async workspace(): Promise<Container> { return this.runtime().workspace() }
        """.trimIndent() + "\n" + methods.joinToString("\n") + "\n" + workerMethod.prependIndent("  ") + "\n}\n"
        outputDirectory.get().asFile.apply {
            mkdirs(); resolve("index.ts").writeText(source)
            resolve("toolchain.json").writeText(toolchainContract.get() + "\n")
            runtimeSource.get().asFile.copyTo(resolve("runtime.ts"), overwrite = true)
        }
        moduleConfiguration.get().asFile.apply {
            parentFile.mkdirs()
            writeText(buildJsonObject {
                put("name", buildName.get())
                put("engineVersion", "v${engineVersion.get()}")
                putJsonObject("sdk") { put("source", "typescript") }
                put("source", moduleSource.get())
            }.toString() + "\n")
        }
    }

    private fun quote(value: String) = JsonPrimitive(value).toString()
}
