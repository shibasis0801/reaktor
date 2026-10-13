package dev.shibasis.dependeasy.desktop

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.tasks.JavaExec
import java.io.File
import java.io.Serializable

abstract class RuntimeLeaseService : BuildService<RuntimeLeaseService.Parameters>, AutoCloseable {
    interface Parameters : BuildServiceParameters {
        val directory: DirectoryProperty
        val downloadedArtifacts: DirectoryProperty
    }
    private val leases = mutableMapOf<String, RuntimeLease>()
    @Synchronized internal fun acquire(id: String, files: Collection<File>): List<File> =
        RuntimeJarStore(parameters.directory.get().asFile, parameters.downloadedArtifacts.get().asFile)
            .acquire(files).also { leases.put(id, it)?.close() }.entries

    @Synchronized fun release(id: String) { leases.remove(id)?.close() }
    @Synchronized override fun close() { leases.values.forEach(RuntimeLease::close); leases.clear() }
}

internal fun Project.stableRuntime(task: String, directory: File) {
    val lease = gradle.sharedServices.registerIfAbsent("$path:$task:runtime", RuntimeLeaseService::class.java) {
        parameters.directory.set(directory)
        parameters.downloadedArtifacts.set(gradle.gradleUserHomeDir.resolve("caches"))
    }
    tasks.withType(JavaExec::class.java).matching { it.name == task }.configureEach {
        usesService(lease)
        doFirst(RetainClasspath(lease, objects.fileCollection()))
        doLast(ReleaseClasspath(lease))
    }
}

private class RetainClasspath(private val lease: Provider<RuntimeLeaseService>, private val retained: ConfigurableFileCollection) : Action<Task>, Serializable {
    override fun execute(target: Task) {
        val task = target as JavaExec
        retained.setFrom(lease.get().acquire(task.path, task.classpath.files))
        task.classpath = retained
    }
}

private class ReleaseClasspath(private val lease: Provider<RuntimeLeaseService>) : Action<Task>, Serializable {
    override fun execute(task: Task) { lease.get().release(task.path) }
}
