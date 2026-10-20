package dev.shibasis.dependeasy.components

import dev.shibasis.dependeasy.common.dependencyNotation
import dev.shibasis.dependeasy.darwin.appleLibrary as bindAppleLibrary
import dev.shibasis.dependeasy.model.ModuleHandle
import dev.shibasis.dependeasy.model.SourceSite
import dev.shibasis.dependeasy.plugins.AppleLibraryPlugin
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project

fun Project.appleLibrary(name: String, exports: List<ModuleHandle> = emptyList(), extensionSafe: Boolean = false) {
    require(plugins.hasPlugin(AppleLibraryPlugin::class.java)) { "Apply dev.shibasis.dependeasy.apple-library" }
    declarationSite()?.let { extensions.add(SourceSite::class.java, "dependeasyComponentDeclaration", it) }
    bindAppleLibrary(name, exports.map(::dependencyNotation), extensionSafe)
    publishLibraryRoles(emptySet())
}
