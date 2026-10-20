package dev.shibasis.dependeasy.desktop

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.toolchain.buildHost
import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.configuration.resolve
import dev.shibasis.dependeasy.handles.publishOutput
import dev.shibasis.dependeasy.model.OutputKind
import dev.shibasis.dependeasy.model.OptionSource
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import dev.shibasis.dependeasy.Versions
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.desktop.DesktopExtension
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.module.compiledJvmEntry
import dev.shibasis.dependeasy.module.entryClass
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.plugins.ApplicationPlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.Task
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import java.io.File
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

internal fun Project.desktopApplication(spec: DesktopAppSpec) {
    val declaration = extensions.findByType(SourceSite::class.java)
    val entry = compiledJvmEntry(ApplicationPlugin.TASK_RUN_NAME, CompilationRef.Main)
    val mac = buildHost.mac
    val sharing = resolve(FrameworkOptions.DesktopClassSharing)
    val application = extensions.getByType<ComposeExtension>().extensions.getByType<DesktopExtension>().application
    application.apply {
        mainClass = null
        javaHome = extensions.getByType<JavaToolchainService>().launcherFor {
            languageVersion.set(JavaLanguageVersion.of(Versions.SDK.Java.asInt))
        }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dcompose.application.configure.swing.globals=false")
        if (mac) jvmArgs("-Dsun.java2d.metal=true", "-Dsun.java2d.opengl=false",
            "--add-exports=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        jvmArgs(*spec.runtime.properties.map { (name, value) -> "-D$name=$value" }.toTypedArray())
        spec.runtime.initialHeap?.let { jvmArgs("-Xms${it.megabytes}m") }
        nativeDistributions {
            targetFormats(*spec.installers.map { when (it) {
                Installer.Dmg -> TargetFormat.Dmg
                Installer.Msi -> TargetFormat.Msi
                Installer.Deb -> TargetFormat.Deb
            } }.toTypedArray())
            packageName = spec.packageName
            packageVersion = spec.version
            spec.outputDirectory?.let { outputBaseDir.set(project.file(it)) }
            modules(*spec.modules.map { it.moduleName }.toTypedArray())
        }
        if (mac) jvmArgs("-Xdock:name=${nativeDistributions.packageName}")
        if (sharing.get().let { it.evidence.source != OptionSource.Default && it.value }) {
            val args = sharedClassArchive(jvmArgs, "${option(BuildOption.systemProperty("user.home")).get()}/.reaktor/cds", project.name)
            jvmArgs.clear()
            jvmArgs.addAll(args)
        }
    }
    tasks.withType<Jar>().configureEach {
        isZip64 = true
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC")
    }
    tasks.withType<AbstractJLinkTask>().configureEach {
        retainJavaLauncher(javaHome, destinationDir, sharing.map { it.value })
    }
    val mainDistribution = application.nativeDistributions.outputBaseDir.dir("main/app")
    val distributable = tasks.withType(AbstractJPackageTask::class.java).matching {
        it.targetFormat == TargetFormat.AppImage && it.destinationDir.get().asFile == mainDistribution.get().asFile
    }
    val output = files(mainDistribution).builtBy(distributable)
    publishOutput(OutputKind.JvmDistribution, output)
    afterEvaluate {
        fun TaskProvider<out Task>.consumeEntry() {
            configure { declaration?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) } }
            node(this).consumes(entry)
        }
        tasks.withType<JavaExec>().names.filter { it in setOf(ApplicationPlugin.TASK_RUN_NAME, "runRelease", "hotRun") }.forEach { name ->
            val task = tasks.named(name, JavaExec::class.java)
            task.configure {
                mainClass.set(entry.entryClass)
                extensions.add(KernelSpec::class.java, "dependeasyKernel", KernelSpec.JvmProgram(CompilationRef.Main, entry.producer.id, spec.run))
                spec.run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
            }
            task.consumeEntry()
            BuildWorkspace.get(project).reference(project, task, null, spec.run.host, "jvm")
        }
        stableRuntime(tasks.named(ApplicationPlugin.TASK_RUN_NAME, JavaExec::class.java),
            File(option(BuildOption.systemProperty("user.home")).get(), ".reaktor/desktop-runtime"))
        tasks.withType<Test>().names.forEach { name ->
            BuildWorkspace.get(project).reference(project, tasks.named(name, Test::class.java), null,
                Run.onDesktopSession().host, "jvm")
        }
        tasks.withType<AbstractProguardTask>().names.forEach { name ->
            val task = tasks.named(name, AbstractProguardTask::class.java)
            task.configure { mainClass.set(entry.entryClass) }
            task.consumeEntry()
        }
        tasks.withType<Jar>().names.filter { it.endsWith("UberJarForCurrentOS") }.forEach { name ->
            val task = tasks.named(name, Jar::class.java)
            task.configure { manifest.attributes["Main-Class"] = entry.entryClass }
            task.consumeEntry()
        }
        tasks.withType<AbstractJPackageTask>().names.forEach { name ->
            val task = tasks.named(name, AbstractJPackageTask::class.java)
            task.configure { launcherMainClass.set(entry.entryClass) }
            task.consumeEntry()
        }
        tasks.withType<AbstractJPackageTask>().configureEach {
            if (sharing.get().value) {
                val base = launcherJvmArgs.get()
                val home = if (project.buildHost.windows) "\$USERPROFILE" else "\$HOME"
                launcherJvmArgs.set(files.elements.map { entries ->
                    sharedClassArchive(base, "$home/.reaktor/cds", packageName.get(), entries.map { it.asFile })
                })
            }
        }
    }
}
