package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile

object DarwinArchiveKernel {
    fun symbolUuids(text: String): Set<Pair<String, String>> {
        val lines = text.lineSequence().filter { it.startsWith("UUID:") }.toList()
        require(lines.isNotEmpty()) { "Missing symbol UUID" }
        return lines.map { line ->
            val match = Regex("^UUID: ([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}) \\(([^)]+)\\) ").find(line)
            requireNotNull(match) { "Unreadable symbol UUID" }
            match.groupValues[1].uppercase() to match.groupValues[2]
        }.toSet()
    }

    private val privacySymbols = mapOf("SFSpeechRecognizer" to "NSSpeechRecognitionUsageDescription", "AVCaptureDevice" to "NSCameraUsageDescription",
        "AVAudioRecorder" to "NSMicrophoneUsageDescription", "CLLocationManager" to "NSLocationWhenInUseUsageDescription",
        "PHPhotoLibrary" to "NSPhotoLibraryUsageDescription", "CNContactStore" to "NSContactsUsageDescription", "EKEventStore" to "NSCalendarsUsageDescription",
        "ATTrackingManager" to "NSUserTrackingUsageDescription", "CMMotionManager" to "NSMotionUsageDescription", "CBCentralManager" to "NSBluetoothAlwaysUsageDescription",
        "HKHealthStore" to "NSHealthShareUsageDescription", "MPMediaLibrary" to "NSAppleMusicUsageDescription")

    fun checkPrivacy(binary: ByteArray, plist: JsonObject) {
        val symbols = binary.toString(Charsets.ISO_8859_1)
        val missing = privacySymbols.filter { (symbol, key) -> symbol in symbols && plist[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
        require(missing.isEmpty()) { "Missing Info.plist purpose strings: ${missing.values.joinToString()}" }
    }

    fun requireMatchingSymbols(binaries: List<Set<Pair<String, String>>>, symbols: List<Set<Pair<String, String>>>): List<Int> {
        val required = binaries.flatten().toSet()
        require(required.isNotEmpty() && binaries.all { uuids -> uuids.any { it.second == "arm64" } }) { "Expected physical arm64 binaries" }
        require((required - symbols.flatten().toSet()).isEmpty()) { "Missing matching archive dSYM UUID/architecture" }
        return symbols.indices.filter { index -> symbols[index].any { it in required } }
    }

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySymbolUpload(evidence: JsonObject, result: JsonObject, ipa: File, uploader: File) {
        fun matches(file: File, hash: JsonElement) = require(sha256(file) == hash.jsonPrimitive.content) { "Release artifact changed during symbol upload" }
        require(result.getValue("succeeded").jsonPrimitive.boolean) { "Symbol provider upload failed" }
        matches(ipa, evidence.getValue("ipaSha256")); matches(uploader, evidence.getValue("uploaderSha256"))
        matches(File(evidence.getValue("firebaseConfigPath").jsonPrimitive.content), evidence.getValue("firebaseConfigSha256"))
        val symbols = evidence.getValue("symbols").jsonArray.map { it.jsonObject }
        require(symbols.isNotEmpty()) { "Missing qualified symbols" }
        require(result.getValue("uploaded").jsonArray.map { it.jsonPrimitive.content }.toSet() == symbols.map { it.getValue("path").jsonPrimitive.content }.toSet()) { "Provider upload did not cover the qualified symbols" }
        symbols.forEach { symbol ->
            val path = File(symbol.getValue("path").jsonPrimitive.content).resolve("Contents/Resources/DWARF")
            symbol.getValue("dwarfSha256").jsonObject.forEach { (name, hash) ->
                require(File(name).name == name) { "Invalid DWARF name" }; matches(path.resolve(name), hash)
            }
        }
    }

    fun check(spec: KernelSpec.DarwinArchive, command: (List<String>) -> String) {
        File(spec.receipt).delete()
        if (spec.verification) {
            val evidence = Json.parseToJsonElement(File(requireNotNull(spec.qualification)).readText()).jsonObject
            val result = Json.parseToJsonElement(File(requireNotNull(spec.providerResult)).readText()).jsonObject
            verifySymbolUpload(evidence, result, File(spec.ipa), File(spec.uploader))
            File(spec.receipt).apply { parentFile.mkdirs(); writeText(JsonObject(evidence + ("providerUploadAccepted" to JsonPrimitive(true))).toString() + "\n") }
            return
        }
        val directory = File(spec.scratch).apply { deleteTreeSafely(within = parentFile); mkdirs() }
        fun plist(file: File) = Json.parseToJsonElement(command(AppleArchiveTools.plist(file))).jsonObject
        fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
        fun uuids(file: File) = symbolUuids(command(AppleArchiveTools.uuids(file)))
        fun encoded(values: Set<Pair<String, String>>) = JsonArray(values.sortedWith(compareBy({ it.first }, { it.second })).map {
            JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })
        val dsymDirectory = File(spec.archive).resolve("dSYMs")
        val dsymPaths = dsymDirectory.listFiles()?.filter { it.isDirectory && it.extension == "dSYM" }?.sortedBy { it.name }.orEmpty()
        require(dsymPaths.isNotEmpty()) { "Missing archive dSYM bundles" }
        val symbolUuids = dsymPaths.map(::uuids)
        val hashes = dsymPaths.map { path -> path.resolve("Contents/Resources/DWARF").listFiles()?.filter(File::isFile)?.associate { it.name to sha256(it) }
            .orEmpty().also { require(it.isNotEmpty()) { "Missing DWARF files" } } }
        val ipaHash = sha256(File(spec.ipa))
        val uploaderHash = sha256(File(spec.uploader))
        require(File(spec.uploader).canExecute()) { "Crashlytics uploader is not executable" }
        try {
            ZipFile(File(spec.ipa)).use { zip ->
                val entries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("Payload/") }.toList()
                val apps = entries.map { it.name.split('/').take(2).joinToString("/") }.distinct().filter { it.endsWith(".app") }
                require(apps.size == 1) { "Expected one packaged iOS app" }
                fun extract(name: String): File {
                    require(name.split('/').none { it == ".." } && !File(name).isAbsolute) { "Invalid IPA path" }
                    val entry = requireNotNull(zip.getEntry(name)) { "Missing packaged file: $name" }
                    require(entry.size in 1..512_000_000) { "Invalid packaged file size" }
                    return directory.resolve(name).apply { parentFile.mkdirs(); zip.getInputStream(entry).use { source -> outputStream().use(source::copyTo) } }
                }
                val app = apps.single()
                val info = plist(extract("$app/Info.plist"))
                require(info.value("CFBundleIdentifier") == spec.bundleId) { "Packaged bundle ID does not match release target" }
                require(info.value("CFBundleShortVersionString") == spec.versionName && info.value("CFBundleVersion") == spec.buildNumber.toString()) { "Packaged version does not match release plan" }
                val google = extract("$app/GoogleService-Info.plist")
                val firebase = plist(google)
                require(firebase.value("BUNDLE_ID") == spec.bundleId && firebase.value("GOOGLE_APP_ID") == plist(File(spec.firebaseConfig)).value("GOOGLE_APP_ID")) { "Packaged Firebase app does not match release target" }
                val bundles = listOf(app) + entries.filter { it.name.endsWith("/Info.plist") }.map { it.name.removeSuffix("/Info.plist") }
                    .filter { it.endsWith(".appex") || it.endsWith(".framework") }.distinct().sorted()
                val binaryUuids = mutableListOf<Set<Pair<String, String>>>()
                val binaries = bundles.map { bundle ->
                    val metadata = if (bundle == app) info else plist(extract("$bundle/Info.plist"))
                    val executable = metadata.value("CFBundleExecutable")
                    require(executable.isNotBlank() && File(executable).name == executable) { "Invalid packaged executable name" }
                    val binary = extract("$bundle/$executable")
                    checkPrivacy(binary.readBytes(), metadata)
                    val ids = uuids(binary); binaryUuids.add(ids)
                    buildJsonObject { put("path", "$bundle/$executable"); put("uuids", encoded(ids)); put("sha256", sha256(binary)) }
                }
                val matching = requireMatchingSymbols(binaryUuids, symbolUuids)
                val config = File(requireNotNull(spec.packagedFirebaseConfig)).apply { parentFile.mkdirs(); google.copyTo(this, overwrite = true) }
                Files.setPosixFilePermissions(config.toPath(), PosixFilePermissions.fromString("rw-------"))
                hashes.forEachIndexed { index, files -> files.forEach { (name, hash) ->
                    require(sha256(dsymPaths[index].resolve("Contents/Resources/DWARF/$name")) == hash) { "dSYM changed during qualification" }
                } }
                val symbols = matching.map { index -> buildJsonObject {
                    put("path", dsymPaths[index].absolutePath); put("uuids", encoded(symbolUuids[index]))
                    put("dwarfSha256", buildJsonObject { hashes[index].forEach { (name, hash) -> put(name, hash) } }) } }
                require(ipaHash == sha256(File(spec.ipa)) && uploaderHash == sha256(File(spec.uploader))) { "Release artifacts changed during qualification" }
                File(spec.receipt).apply { parentFile.mkdirs(); writeText(buildJsonObject {
                    put("version", 1); put("ipaSha256", ipaHash); put("appBundleId", spec.bundleId); put("firebaseAppId", firebase.value("GOOGLE_APP_ID"))
                    put("appVersion", spec.versionName); put("appBuild", spec.buildNumber); put("packagedBinaries", JsonArray(binaries)); put("symbols", JsonArray(symbols))
                    put("uploaderSha256", uploaderHash); put("firebaseConfigSha256", sha256(config)); put("firebaseConfigPath", config.absolutePath)
                    put("providerUploadAccepted", false); put("serverSymbolProcessing", "Unknown"); put("physicalCrashFrames", "Not qualified")
                }.toString() + "\n") }
            }
        } finally {
            require(directory.deleteTreeSafely(within = directory.parentFile)) { "Could not remove owned archive-check scratch" }
        }
    }
}
