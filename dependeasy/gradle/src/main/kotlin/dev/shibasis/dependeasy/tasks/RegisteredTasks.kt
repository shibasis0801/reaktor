package dev.shibasis.dependeasy.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

internal fun <T : Task> Project.registeredTask(type: Class<T>, key: String = type.name,
    register: () -> TaskProvider<T>): TaskProvider<T> {
    @Suppress("UNCHECKED_CAST")
    return if (extensions.extraProperties.has(key)) extensions.extraProperties.get(key) as TaskProvider<T>
        else register().also { extensions.extraProperties.set(key, it) }
}
