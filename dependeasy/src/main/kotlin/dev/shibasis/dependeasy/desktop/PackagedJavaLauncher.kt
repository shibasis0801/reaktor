package dev.shibasis.dependeasy.desktop

import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Provider
import org.gradle.process.ExecOperations
import java.io.Serializable
import javax.inject.Inject

/** Retain Java for background processes when a runtime image strips native commands. */
fun Task.retainJavaLauncher(
    javaHome: Provider<String>, destination: DirectoryProperty, classSharing: Provider<Boolean>,
) {
    val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    inputs.file(javaHome.map { "$it/bin/$executable" }).withPropertyName("backgroundJavaLauncher")
    inputs.property("desktopClassSharing", classSharing)
    doLast(project.objects.newInstance(RetainJavaLauncher::class.java, javaHome, destination, classSharing, executable))
}

abstract class RetainJavaLauncher @Inject constructor(
    private val javaHome: Provider<String>,
    private val destination: DirectoryProperty,
    private val classSharing: Provider<Boolean>,
    private val executable: String,
) : Action<Task>, Serializable {
    @get:Inject abstract val execOperations: ExecOperations

    override fun execute(task: Task) {
        val launcher = destination.get().file("bin/$executable").asFile
        launcher.parentFile.mkdirs()
        java.io.File(javaHome.get(), "bin/$executable").copyTo(launcher, overwrite = true)
        check(launcher.setExecutable(true, false)) { "Could not enable the background Java launcher" }
        if (classSharing.get()) execOperations.exec {
            commandLine(launcher.absolutePath, "-Xshare:dump")
        }.assertNormalExitValue()
    }
}
