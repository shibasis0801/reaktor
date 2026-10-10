package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.module.jvmExecution
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

@DisableCachingByDefault(because = "Executes a probe against the host-specific packaged runtime")
abstract class PackagedRuntimeTest : JavaExec() {
    @get:Inject abstract val runtimeFiles: ObjectFactory
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distribution: DirectoryProperty
    @get:Input abstract val packageName: Property<String>
    @get:Classpath abstract val probeClasspath: ConfigurableFileCollection

    @TaskAction override fun exec() {
        val root = distribution.get().asFile
        val release = root.walkTopDown().single { it.isFile && it.name == "release" &&
            it.readLines().any { line -> line.startsWith("MODULES=") } }
        val modules = release.readLines().single { it.startsWith("MODULES=") }
            .substringAfter('=').trim('"').split(' ').joinToString(",")
        val configuration = root.walkTopDown().single { it.name == "${packageName.get()}.cfg" }
        val jars = configuration.readLines().filter { it.startsWith("app.classpath=") }.map {
            File(configuration.parentFile, it.substringAfter("\$APPDIR/"))
        }
        check(jars.isNotEmpty() && jars.all(File::isFile)) { "Packaged runtime classpath is incomplete" }
        classpath = probeClasspath.plus(runtimeFiles.fileCollection().from(jars))
        jvmArgs("--limit-modules=$modules", "--enable-native-access=ALL-UNNAMED")
        super.exec()
    }

}

internal fun Project.packagedRuntimeTest(name: String, main: String, compilation: String,
                                       configuration: PackagedRuntimeTest.() -> Unit) = run {
    val execution = jvmExecution(compilation)
    val launcher = extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
    }
    tasks.register<PackagedRuntimeTest>(name) {
        group = "verification"
        dependsOn("createDistributable", execution.classes)
        mainClass.set(main)
        javaLauncher.set(launcher)
        probeClasspath.from(execution.output)
        configuration()
    }
}
