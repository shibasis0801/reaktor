package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.Aspect
import dev.shibasis.dependeasy.model.FileSet
import org.gradle.api.Project

internal fun buildTooling(project: Project) =
    JavaScriptComponent(project, "buildTooling", JavaScriptRuntime(project).directory).run {
        sources(JavaScriptRuntime(project).sources)
        val tests = check(FileSet(listOf("test/*.mjs")))
        project.node(tests).covers(Aspect.Unit)
        build("checkBuildTooling", bundle = tests)
    }
