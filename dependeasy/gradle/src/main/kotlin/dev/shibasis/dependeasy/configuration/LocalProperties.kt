package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.workspace.declarationSite
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

internal fun Project.releaseProperty(option: BuildOption<Boolean>): Provider<Boolean> = releaseRequirement(option, false).first

internal fun Project.releaseRequirement(option: BuildOption<Boolean>, expected: Boolean,
    site: SourceSite? = declarationSite()): Pair<Provider<Boolean>, dev.shibasis.dependeasy.handles.NodeHandle> {
    val effective = resolve(option).zip(option(BuildOption.environment("CONFIGURATION", ""))) { resolved, configuration ->
        if (configuration == "Release") ResolvedOption(expected, resolved.evidence.copy(value = expected.toString(), source = OptionSource.XcodeRelease)) else resolved
    }
    val build = rootProject.name
    val project = path
    val requirement = tasks.register<dev.shibasis.dependeasy.workspace.WorkspaceNodeTask>(
        "require${option.name.split('.', '-').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }}${if (expected) "Enabled" else "Disabled"}ForRelease"
    ) {
        bind(VerificationKernel)
        site?.let { extensions.add(SourceSite::class.java, "dependeasyDeclaration", it) }
        val identity = NodeId(build, project, name)
        declaration.set(effective.map { resolved -> BuildModelReader.json.encodeToString(
            KernelSpec.WorkspaceNode(identity, NodeKind.Verification, Run.Default,
                emptyList(), emptyList(), emptyList(), listOf(OptionRequirement(resolved.evidence, expected.toString())))) })
    }
    val producer = node(requirement)
    pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
            binaries.configureEach {
                if (buildType == NativeBuildType.RELEASE) node(linkTaskProvider, OutputKind.AppleFramework).after(producer)
            }
        }
        kotlin.targets.withType(KotlinAndroidTarget::class.java).configureEach {
            compilations.matching { it.name == "release" }.configureEach {
                node(compileTaskProvider).after(producer)
            }
        }
    }
    return effective.map { it.value } to producer
}
