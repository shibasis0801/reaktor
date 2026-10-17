package dev.shibasis.dependeasy.web

import org.gradle.api.Project

internal fun buildTooling(project: Project) =
    JavaScriptComponent(project, "buildTooling", JavaScriptRuntime(project).directory).run {
        sources(JavaScriptRuntime(project).sources)
        build("checkBuildTooling", bundle = check())
    }
