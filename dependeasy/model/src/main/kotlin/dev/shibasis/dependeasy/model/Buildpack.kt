package dev.shibasis.dependeasy.model

import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*
import org.springframework.boot.buildpack.platform.build.*
import org.springframework.boot.buildpack.platform.docker.*
import org.springframework.boot.buildpack.platform.docker.configuration.*
import org.springframework.boot.buildpack.platform.docker.transport.HttpTransport
import org.springframework.boot.buildpack.platform.docker.type.ImageReference

object Buildpack {
    fun build(spec: ServerImageSpec, reference: String) {
        val request = BuildRequest.forJarFile(ImageReference.of(reference), File(spec.jar))
            .withBuilder(ImageReference.of(spec.builder)).withRunImage(ImageReference.of(spec.runImage))
            .withPullPolicy(PullPolicy.IF_NOT_PRESENT).withPublish(false).withImagePlatform("linux/amd64")
            .withEnv(mapOf("BP_NATIVE_IMAGE" to "false", "BP_JVM_VERSION" to spec.javaVersion.toString(), "BP_JVM_TYPE" to "JRE",
                "JAVA_TOOL_OPTIONS" to "-XX:+ExitOnOutOfMemoryError -XX:+UseG1GC -XX:MaxRAMPercentage=75 -XX:InitialRAMPercentage=50 -XX:MaxMetaspaceSize=256m"))
        Builder(connection(spec)).build(request)
    }

    fun tag(spec: ServerImageSpec, source: String, destination: String) {
        api(spec).image().tag(ImageReference.of(source), ImageReference.of(destination))
    }

    fun push(spec: ServerImageSpec, reference: String) {
        val image = ImageReference.of(reference)
        val auth = DockerRegistryAuthentication.configuration(null) { _, _ ->
            throw IllegalStateException("Declared registry credential helper failed; output withheld")
        }
        api(spec).image().push(image, UpdateListener.none(), auth.getAuthHeader(image))
    }

    fun inspect(spec: ServerImageSpec, reference: String): JsonObject {
        val path = "/images/" + URLEncoder.encode(reference, StandardCharsets.UTF_8) + "/json"
        return HttpTransport.create(connection(spec).connection()).get(URI(path)).use { response ->
            val bytes = response.content.readNBytes(1_048_577)
            require(bytes.size <= 1_048_576) { "Image metadata exceeds its bounded read" }
            BuildModelReader.json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }
    }

    private fun connection(spec: ServerImageSpec) = BuilderDockerConfiguration().withHost(spec.dockerHost, false, null)
    private fun api(spec: ServerImageSpec) = DockerApi(connection(spec).connection(), DockerLog { })
}
