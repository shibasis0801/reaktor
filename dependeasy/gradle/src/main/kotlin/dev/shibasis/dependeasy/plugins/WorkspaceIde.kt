package dev.shibasis.dependeasy.plugins

import org.gradle.api.Project
import org.gradle.plugins.ide.idea.model.IdeaModel

internal fun configureWorkspaceIde(project: Project) {
    project.pluginManager.apply("idea")
    project.extensions.getByType(IdeaModel::class.java).module.excludeDirs.add(project.file(".github_modules"))
}
