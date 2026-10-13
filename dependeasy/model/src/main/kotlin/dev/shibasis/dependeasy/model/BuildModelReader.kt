package dev.shibasis.dependeasy.model

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object BuildModelReader {
    const val RelativePath = "build/dependeasy/model.json"
    const val MaxBytes = 8 * 1024 * 1024
    val json = Json { encodeDefaults = true }

    fun decode(text: String): BuildModel {
        require(text.toByteArray(Charsets.UTF_8).size <= MaxBytes) { "Build model exceeds $MaxBytes bytes" }
        val model = json.decodeFromString<BuildModel>(text)
        require(model.schema == 2) { "Unsupported build model schema ${model.schema}; exportBuildModel must be rerun" }
        require(model.builds.map { it.name }.distinct().size == model.builds.size) { "Duplicate build identity" }
        require(model.nodes.map { it.id }.distinct().size == model.nodes.size) { "Duplicate build node identity" }
        val builds = model.builds.map { it.name }.toSet()
        require(model.nodes.all { it.id.build in builds }) { "Unknown node build" }
        require(model.declarations.all { it.build in builds }) { "Unknown declaration build" }
        return model
    }

    fun read(root: File): BuildModel {
        val file = root.resolve(RelativePath)
        require(file.isFile) { "Run ./gradlew exportBuildModel in this workspace" }
        val bytes = file.inputStream().use { it.readNBytes(MaxBytes + 1) }
        require(bytes.size <= MaxBytes) { "Build model exceeds $MaxBytes bytes" }
        val model = decode(bytes.toString(Charsets.UTF_8))
        val directories = model.builds.associate { it.name to root.resolve(it.directory).canonicalFile }
        val stale = model.declarations.filter { input ->
            val owner = directories.getValue(input.build)
            val declaration = owner.resolve(input.path).canonicalFile
            require(declaration.toPath().startsWith(owner.toPath())) { "Declaration escapes its build" }
            !declaration.isFile || digest(declaration) != input.sha256
        }
        require(stale.isEmpty()) { "Build model is stale: ${stale.size} declaration inputs changed; rerun exportBuildModel" }
        return model
    }

    fun node(root: File, id: String): Node = read(root).nodes.single { it.id.toString() == id }
    fun encode(model: BuildModel): String = json.encodeToString(model) + "\n"
    fun encode(node: Node): String = json.encodeToString(node)
    fun decodeNode(text: String): Node = json.decodeFromString(text)
    fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
}
