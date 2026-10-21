package dev.shibasis.dependeasy.server

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.GradlePlugins
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import dev.shibasis.dependeasy.workspace.declarationSite
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.gradle.api.Project

internal fun Project.serverRelease(name: String, archive: OutputHandle, repository: String, kubeconfig: String,
    environment: Environment, manifest: JsonObject, gate: NodeHandle): NodeHandle {
    val metadata = manifest.getValue("metadata").jsonObject
    val namespace = metadata.getValue("namespace").jsonPrimitive.content
    val deployment = metadata.getValue("name").jsonPrimitive.content
    val container = manifest.getValue("spec").jsonObject.getValue("template").jsonObject.getValue("spec").jsonObject
        .getValue("containers").jsonArray.single().jsonObject.getValue("name").jsonPrimitive.content
    require(archive.kind == OutputKind.ServerArchive) { "A server release needs its component's archive output" }
    val site = declarationSite()
    val files = outputFiles(archive)
    val source = layout.file(providers.provider { files.singleFile })
    val image = ServerImageSpec("", repository, "unix:///var/run/docker.sock", Versions.SDK.Java.asInt,
        ToolchainVersions.BuildpackBuilder, ToolchainVersions.BuildpackRunImage, ToolRef("spring-boot-buildpack-platform", GradlePlugins.SpringBoot))
    val build = tasks.register("${name}Image", BuildServerImage::class.java) {
        group = "delivery"
        declaration.set(BuildModelReader.json.encodeToString(image))
        this.archive.set(source)
        descriptor.set(layout.buildDirectory.file("dependeasy/contracts/server/$name/built.json"))
        site?.let { site -> extensions.add(SourceSite::class.java, "dependeasyDeclaration", site) }
    }
    val built = artifact(build) { it.descriptor }
    BuildWorkspace.get(this).consume(node(build).after(gate), archive)
    val push = tasks.register("${name}Push", PushServerImage::class.java) {
        group = "delivery"
        declaration.set(BuildModelReader.json.encodeToString(image))
        this.archive.set(source)
        this.built.set(built.location)
        descriptor.set(layout.buildDirectory.file("dependeasy/contracts/server/$name/published.json"))
        site?.let { site -> extensions.add(SourceSite::class.java, "dependeasyDeclaration", site) }
    }
    node(push).consumes(built)
    val published = artifact(push) { it.descriptor }
    val rollout = tasks.register(name, RolloutServer::class.java) {
        group = "delivery"
        workingDirectory.set(layout.projectDirectory)
        executable.set("kubectl")
        toolVersion.set("host-provided (unversioned)")
        generatedManifest.set(layout.buildDirectory.file("dependeasy/contracts/server/$name/deployment.json"))
        this.archive.set(source)
        this.published.set(published.location)
        declaration.set(BuildModelReader.json.encodeToString(ServerRolloutSpec(NodeId(rootProject.name, this@serverRelease.path, name),
            environment, ServiceRef(deployment), ProcessExecution(rootDir.absolutePath, "kubectl", emptyList(), emptyList(),
                ToolRef("kubectl", "host-provided (unversioned)"), emptyList(), emptyList()),
            file(kubeconfig).absolutePath, namespace, deployment, container, repository, manifest)))
        site?.let { site -> extensions.add(SourceSite::class.java, "dependeasyDeclaration", site) }
    }
    return node(rollout).consumes(published)
}
