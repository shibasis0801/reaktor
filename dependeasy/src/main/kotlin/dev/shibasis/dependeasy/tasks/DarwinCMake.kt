package dev.shibasis.dependeasy.tasks

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

fun Project.darwinCmake(sdk: String): TaskProvider<out Task>? {
    return kotlinCmake(CmakePlatform.Darwin(sdk))
}
