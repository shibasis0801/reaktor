package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.json.*

object ServerImageKernel {
    fun build(spec: ServerImageSpec): BuiltServerImage {
        val jar = File(spec.jar)
        require(jar.isFile) { "Declared server archive is missing" }
        val jarDigest = BuildModelReader.digest(jar)
        val staged = "${spec.repository}:build-$jarDigest"
        Buildpack.build(spec, staged)
        require(BuildModelReader.digest(jar) == jarDigest) { "Server archive changed during image build" }
        val digest = config(Buildpack.inspect(spec, staged))
        val reference = "${spec.repository}:sha256-${digest.removePrefix("sha256:")}"
        Buildpack.tag(spec, staged, reference)
        require(config(Buildpack.inspect(spec, reference)) == digest) { "Built image changed during tagging" }
        return BuiltServerImage(digest, reference, jarDigest)
    }

    fun push(spec: ServerImageSpec, built: BuiltServerImage): PublishedServerImage {
        require(built.configDigest.matches(Regex("sha256:[0-9a-f]{64}")))
        require(built.reference == "${spec.repository}:sha256-${built.configDigest.removePrefix("sha256:")}")
        require(BuildModelReader.digest(File(spec.jar)) == built.jarDigest) { "Server archive changed after image build" }
        require(config(Buildpack.inspect(spec, built.reference)) == built.configDigest) { "Built image was replaced before push" }
        Buildpack.push(spec, built.reference)
        val published = Buildpack.inspect(spec, built.reference)
        require(config(published) == built.configDigest) { "Built image was replaced during push" }
        val digests = published.getValue("RepoDigests").jsonArray.map { it.jsonPrimitive.content }
            .filter { it.matches(Regex(Regex.escape(spec.repository) + "@sha256:[0-9a-f]{64}")) }.distinct()
        require(digests.size == 1) { "Registry did not attest one manifest digest for the built image" }
        val image = digests.single()
        require(config(Buildpack.inspect(spec, image)) == built.configDigest) { "Published manifest does not identify the built config" }
        return PublishedServerImage(image, built.configDigest, built.jarDigest)
    }

    private fun config(value: JsonObject): String {
        require(value.getValue("Os").jsonPrimitive.content == "linux" && value.getValue("Architecture").jsonPrimitive.content == "amd64")
        return value.getValue("Id").jsonPrimitive.content.also {
            require(it.matches(Regex("sha256:[0-9a-f]{64}"))) { "Engine did not return an immutable config identity" }
        }
    }
}
