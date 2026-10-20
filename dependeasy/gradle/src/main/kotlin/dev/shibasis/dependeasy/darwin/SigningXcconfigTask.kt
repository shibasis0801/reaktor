package dev.shibasis.dependeasy.darwin

import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal fun signingFiles(host: AppleHostContract, request: JsonObject): Pair<String, String?> {
    fun setting(text: String, key: String) = text.also {
        require(it.isNotBlank() && '\n' !in it && '\r' !in it) { "Invalid signing value: $key" }
    }
    fun value(key: String) = setting(request.getValue(key).jsonPrimitive.content, key)
    val profiles = request["profiles"]?.jsonObject.orEmpty()
    val application = host.targets.single { it.name == host.applicationTarget }
    val style = request["style"]?.jsonPrimitive?.content ?: if (profiles.isEmpty()) "Automatic" else "Manual"
    require(style in setOf("Automatic", "Manual")) { "Invalid signing style" }
    val config = buildString {
        for (target in host.targets) {
            val prefix = target.settingsPrefix
            append("${prefix}_CODE_SIGN_STYLE = $style\n${prefix}_CODE_SIGN_IDENTITY = ${value("identity")}\n")
            profiles[target.bundleId]?.let {
                append("${prefix}_PROVISIONING_PROFILE_SPECIFIER = ${setting(it.jsonPrimitive.content, target.bundleId)}\n")
            }
        }
        append("${application.settingsPrefix}_APS_ENVIRONMENT = ${value("apsEnvironment")}\n")
    }
    fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val options = request["method"]?.let {
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict>")
            for (key in listOf("method", "teamID", "signingCertificate")) {
                val input = when(key) { "teamID" -> "team"; "signingCertificate" -> "identity"; else -> key }
                append("<key>$key</key><string>${xml(value(input))}</string>")
            }
            append("<key>signingStyle</key><string>${style.lowercase()}</string><key>stripSwiftSymbols</key><true/>")
            append("<key>uploadSymbols</key><true/><key>provisioningProfiles</key><dict>")
            profiles.toSortedMap().forEach { (id, profile) ->
                append("<key>${xml(id)}</key><string>${xml(setting(profile.jsonPrimitive.content, id))}</string>")
            }
            append("</dict></dict></plist>\n")
        }
    }
    return config to options
}

@DisableCachingByDefault(because = "Materializes local signing metadata without caching provider state")
abstract class SigningXcconfigTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelHost get() = HostRequirement(HostPlatform.Macos, ExecutionHost.Local)
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.SigningXcconfig(BuildModelReader.json.decodeFromString(host.get()), request.get().asFile.absolutePath, config.get().asFile.absolutePath, exportOptions.orNull?.asFile?.absolutePath)
    @get:Input abstract val host: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val request: RegularFileProperty
    @get:OutputFile abstract val config: RegularFileProperty
    @get:OutputFile @get:Optional abstract val exportOptions: RegularFileProperty
    @TaskAction fun write() {
        val (settings, options) = signingFiles(BuildModelReader.json.decodeFromString(host.get()), Json.parseToJsonElement(request.get().asFile.readText()).jsonObject)
        fun save(file: java.io.File, text: String) {
            file.parentFile.mkdirs(); file.writeText(text)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        save(config.get().asFile, settings)
        if (options != null) save(exportOptions.get().asFile, options)
    }
}
