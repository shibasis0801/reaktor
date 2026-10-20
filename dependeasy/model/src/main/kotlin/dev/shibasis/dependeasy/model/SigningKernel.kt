package dev.shibasis.dependeasy.model

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.*

object SigningKernel {
    fun render(host: AppleHostContract, request: JsonObject): Pair<String, String?> {
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

    fun write(spec: KernelSpec.SigningXcconfig) {
        val (settings, options) = render(spec.host, Json.parseToJsonElement(File(spec.request).readText()).jsonObject)
        fun save(file: File, text: String) {
            file.parentFile.mkdirs(); file.writeText(text)
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        save(File(spec.config), settings)
        if (options != null) save(File(requireNotNull(spec.exportOptions)), options)
    }
}
