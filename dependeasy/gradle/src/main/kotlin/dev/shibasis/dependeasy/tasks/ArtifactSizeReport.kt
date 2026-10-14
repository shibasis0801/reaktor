package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.workspace.declarations
import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import javax.inject.Inject

@CacheableTask
abstract class ArtifactSizeReport : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:Inject abstract val execOperations: ExecOperations
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val artifacts: ConfigurableFileCollection
    @get:Internal abstract val baseDirectory: DirectoryProperty
    @get:Input val baseDirectoryIdentity: String get() = baseDirectory.get().asFile.canonicalPath
    @get:Input abstract val metrics: ListProperty<SizeMetric>
    @get:Input abstract val budgets: ListProperty<SizeBudget>
    @get:Input abstract val nodeId: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val node: RegularFileProperty
    @get:Input abstract val nodeVersion: Property<String>
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ArtifactSize(
        emptyList(), report.get().asFile.absolutePath, metrics.get(), budgets.get(), ToolRef("node", nodeVersion.get()), artifacts.declarations())

    init {
        val tools = JavaScriptTools.get(project)
        dependsOn(tools.nodeSetup)
        node.set(tools.node)
        nodeVersion.set(dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node)
        baseDirectory.convention(project.layout.projectDirectory)
        metrics.convention(emptyList())
        budgets.convention(emptyList())
        nodeId.convention(NodeId(project.rootProject.name, project.path, name).toString())
    }

    @TaskAction fun measure() {
        val files = artifacts.asFileTree.files.filter { it.isFile }.sortedBy { it.absolutePath }
        val compressible = files.filter { it.extension in setOf("css", "html", "js", "json", "mjs", "svg") }
        val compressed = if (compressible.isEmpty()) emptyMap() else {
            val output = ByteArrayOutputStream()
            execOperations.exec {
                executable = node.get().asFile.absolutePath
                args("-e", "const fs=require('node:fs'),z=require('node:zlib');process.stdout.write(JSON.stringify(JSON.parse(fs.readFileSync(0,'utf8')).map(p=>[p,z.brotliCompressSync(fs.readFileSync(p)).length])))")
                standardInput = ByteArrayInputStream(BuildModelReader.json.encodeToString(compressible.map { it.absolutePath }).toByteArray())
                standardOutput = output
            }
            Json.parseToJsonElement(output.toString(Charsets.UTF_8)).jsonArray.associate {
                val row = it.jsonArray; row[0].jsonPrimitive.content to row[1].jsonPrimitive.long
            }
        }
        val measurements = files.map { file ->
            val gzip = ByteArrayOutputStream()
            object : GZIPOutputStream(gzip) { init { def.setLevel(9) } }.use { zip -> file.inputStream().use { it.copyTo(zip) } }
            ArtifactMeasurement(file.relativeTo(baseDirectory.get().asFile).invariantSeparatorsPath,
                file.length(), gzip.size().toLong(), compressed[file.absolutePath] ?: file.length(), BuildModelReader.digest(file))
        }
        val missing = mutableListOf<String>()
        val values = metrics.get().associate { selector ->
            val selected = measurements.filter { Regex(selector.pathPattern).matches(it.path) }
            if (selector.required && selected.isEmpty()) missing.add(selector.name)
            val value = when (selector.measure) {
                SizeMeasure.Bytes -> selected.sumOf { it.bytes }
                SizeMeasure.Gzip -> selected.sumOf { it.gzip }
                SizeMeasure.Brotli -> selected.sumOf { it.brotli }
                SizeMeasure.Count -> selected.size.toLong()
                SizeMeasure.ContentMatches -> selected.sumOf {
                    Regex(requireNotNull(selector.contentPattern)).findAll(baseDirectory.get().asFile.resolve(it.path).readText()).count().toLong()
                }
            }
            selector.name to value
        }
        val result = ArtifactSizeResult(node = NodeId.parse(nodeId.get()), artifacts = measurements, metrics = values, missing = missing, budgets = budgets.get())
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(BuildModelReader.json.encodeToString(result) + "\n")
        }
    }
}
