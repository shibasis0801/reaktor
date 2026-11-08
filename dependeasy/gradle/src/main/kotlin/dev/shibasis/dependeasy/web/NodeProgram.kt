package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.toolchain.JavaScriptTools
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsBinaryMode
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.js.ir.Library

abstract class NodeProgramTask : DefaultTask() {
    @get:Input abstract val program: Property<String>
    @get:Input abstract val main: Property<String>
    @get:Input abstract val node: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val modules: ConfigurableFileCollection
    @get:OutputDirectory abstract val resources: DirectoryProperty

    @TaskAction fun record() {
        val root = resources.get().asFile
        root.deleteTreeSafely(within = root.parentFile)
        val home = root.resolve("reaktor/node/${program.get()}").apply { mkdirs() }
        val files = modules.files.sortedBy { it.name }
        files.forEach { it.copyTo(home.resolve(it.name)) }
        home.resolve(Entry).writeText("import { start } from ${JsonPrimitive("./" + main.get())};\n\nawait start(process.argv.slice(2));\n")
        val names = files.map { it.name } + Entry
        val digest = MessageDigest.getInstance("SHA-256").apply {
            names.forEach { name -> update(name.toByteArray()); update(0); update(home.resolve(name).readBytes()) }
        }.digest().joinToString("") { "%02x".format(it) }
        root.resolve("reaktor/node/${program.get()}.json").writeText(buildJsonObject {
            put("program", program.get())
            put("node", node.get())
            put("entry", Entry)
            put("digest", digest)
            put("files", JsonArray(names.map(::JsonPrimitive)))
        }.toString() + "\n")
    }

    private companion object {
        const val Entry = "main.mjs"
    }
}

internal fun Project.nodeProgram(name: String): NodeHandle {
    val task = tasks.register<NodeProgramTask>(name) {
        group = "dependeasy"
        description = "Package the linked Kotlin/JS Node program into the JVM resources, with the Node that starts it"
        program.set(project.name)
        node.set(JavaScriptTools.get(project).node.map { it.asFile.absolutePath })
        resources.set(layout.buildDirectory.dir("dependeasy/node/$name/resources"))
    }
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinJsIrTarget::class.java).configureEach {
            compilations.getByName("main").binaries.withType(Library::class.java).configureEach {
                if (mode == KotlinJsBinaryMode.PRODUCTION) {
                    val sync = linkSyncTask
                    val linked = mainFileSyncPath
                    task.configure {
                        dependsOn(sync)
                        main.set(linked.map { it.asFile.name })
                        modules.from(linked.map { file -> project.fileTree(file.asFile.parentFile) { include("*.mjs") } })
                    }
                }
            }
        }
        kotlin.sourceSets.matching { it.name == "jvmMain" }.all { resources.srcDir(task.flatMap { it.resources }) }
    }
    return node(task, OutputKind.GeneratedContract)
}
