package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.configuration.executionOption
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Each invocation observes Gradle on the selected execution host")
abstract class GradleInvocationTask : DefaultTask(), dev.shibasis.dependeasy.process.KernelSpecOwner {
    @get:Internal override val kernelSpec get() = KernelSpec.GradleInvocation(planning.get(),
        modelFile.get().asFile.absolutePath, rootDirectory.file(GradleProtocol.RequestDirectory).get().asFile.absolutePath,
        outputDirectory.get().asFile.absolutePath, ToolRef("gradle", project.gradle.gradleVersion))
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Internal abstract val gradleHome: DirectoryProperty
    @get:Internal abstract val gradleUserHome: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val requestFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val modelFile: RegularFileProperty
    @get:Input abstract val planning: Property<Boolean>
    @get:Input abstract val maxWorkers: Property<Int>
    @get:Input abstract val javaHome: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val initScripts: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init {
        javaHome.convention(project.executionOption(BuildOption.systemProperty("java.home")))
        outputs.upToDateWhen { false }
    }

    @TaskAction fun invoke() {
        val root = rootDirectory.get().asFile.canonicalFile
        val model = BuildModelReader.read(root)
        val invocation = GradleInvocationKernel.validate(root, requestFile.get().asFile, model)
        val environment = BuildOption.environmentSnapshot()
        val context = GradleInvocationKernel.context(javaHome.get(), gradleHome.get().asFile,
            gradleUserHome.get().asFile, initScripts.files, environment, BuildOption.systemPropertySnapshot())
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        if (planning.get() && GradleInvocationKernel.deploymentNodes(model, invocation.node.id).isEmpty() && GradlePlanCache.load(directory, invocation, context)) return
        GradleToolingRunner.invoke(root, gradleHome.get().asFile, gradleUserHome.get().asFile,
            javaHome.get(), maxWorkers.get(), initScripts.files, environment, directory, planning.get(),
            model, invocation, context)
    }
}
