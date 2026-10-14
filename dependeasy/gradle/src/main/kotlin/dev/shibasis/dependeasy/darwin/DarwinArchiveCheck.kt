package dev.shibasis.dependeasy.darwin

import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.serialization.json.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.inject.Inject

internal fun symbolUuids(text: String): Set<Pair<String, String>> {
    val lines = text.lineSequence().filter { it.startsWith("UUID:") }.toList()
    require(lines.isNotEmpty()) { "Missing symbol UUID" }
    return lines.map { line ->
        val match = Regex("^UUID: ([0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}) \\(([^)]+)\\) ").find(line)
        requireNotNull(match) { "Unreadable symbol UUID" }
        match.groupValues[1].uppercase() to match.groupValues[2]
    }.toSet()
}

internal val privacySymbols = mapOf("SFSpeechRecognizer" to "NSSpeechRecognitionUsageDescription", "AVCaptureDevice" to "NSCameraUsageDescription",
    "AVAudioRecorder" to "NSMicrophoneUsageDescription", "CLLocationManager" to "NSLocationWhenInUseUsageDescription",
    "PHPhotoLibrary" to "NSPhotoLibraryUsageDescription", "CNContactStore" to "NSContactsUsageDescription", "EKEventStore" to "NSCalendarsUsageDescription",
    "ATTrackingManager" to "NSUserTrackingUsageDescription", "CMMotionManager" to "NSMotionUsageDescription", "CBCentralManager" to "NSBluetoothAlwaysUsageDescription",
    "HKHealthStore" to "NSHealthShareUsageDescription", "MPMediaLibrary" to "NSAppleMusicUsageDescription")

internal fun checkPrivacy(binary: ByteArray, plist: JsonObject) {
    val symbols = binary.toString(Charsets.ISO_8859_1)
    val missing = privacySymbols.filter { (symbol, key) -> symbol in symbols && plist[key]?.jsonPrimitive?.contentOrNull.isNullOrBlank() }
    require(missing.isEmpty()) { "Missing Info.plist purpose strings: ${missing.values.joinToString()}" }
}

internal fun requireMatchingSymbols(binaries: List<Set<Pair<String, String>>>, symbols: List<Set<Pair<String, String>>>): List<Int> {
    val required = binaries.flatten().toSet()
    require(required.isNotEmpty() && binaries.all { uuids -> uuids.any { it.second == "arm64" } }) { "Expected physical arm64 binaries" }
    require((required - symbols.flatten().toSet()).isEmpty()) { "Missing matching archive dSYM UUID/architecture" }
    return symbols.indices.filter { index -> symbols[index].any { it in required } }
}

internal fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun verifySymbolUpload(evidence: JsonObject, result: JsonObject, ipa: File, uploader: File) {
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

@DisableCachingByDefault(because = "Qualifies a signed physical-device archive against its packaged binaries")
abstract class DarwinArchiveCheck @Inject constructor(private val exec: ExecOperations) : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.DarwinArchive(bundleId.get(), ipa.get().asFile.absolutePath, archive.get().asFile.absolutePath, verifyUpload.get())
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(HostPlatform.Macos, ExecutionHost.Local)
    @get:Internal override val kernelEffect: Effect get() = Effect.Verify
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val ipa: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val archive: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val firebaseConfig: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val uploader: RegularFileProperty
    @get:Input abstract val bundleId: Property<String>
    @get:Input abstract val versionName: Property<String>
    @get:Input abstract val buildNumber: Property<Int>
    @get:Internal abstract val scratch: DirectoryProperty
    @get:Input abstract val verifyUpload: Property<Boolean>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val qualification: RegularFileProperty
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE) abstract val providerResult: RegularFileProperty
    @get:OutputFile @get:Optional abstract val packagedFirebaseConfig: RegularFileProperty
    @get:OutputFile abstract val receipt: RegularFileProperty
    init { verifyUpload.convention(false) }
    @TaskAction fun check() {
        receipt.get().asFile.delete()
        if (verifyUpload.get()) {
            val evidence = Json.parseToJsonElement(qualification.get().asFile.readText()).jsonObject
            val result = Json.parseToJsonElement(providerResult.get().asFile.readText()).jsonObject
            verifySymbolUpload(evidence, result, ipa.get().asFile, uploader.get().asFile)
            receipt.get().asFile.apply { parentFile.mkdirs(); writeText(JsonObject(evidence + ("providerUploadAccepted" to JsonPrimitive(true))).toString() + "\n") }
            return
        }
        val directory = scratch.get().asFile.apply { deleteTreeSafely(within = parentFile); mkdirs() }
        fun command(vararg tokens: String): String = ByteArrayOutputStream().use { output ->
            exec.exec { commandLine(*tokens); standardOutput = output }.assertNormalExitValue()
            output.toString(Charsets.UTF_8)
        }
        fun plist(file: File) = Json.parseToJsonElement(command("/usr/bin/plutil", "-convert", "json", "-o", "-", file.absolutePath)).jsonObject
        fun JsonObject.value(key: String) = getValue(key).jsonPrimitive.content
        fun uuids(file: File) = symbolUuids(command("xcrun", "dwarfdump", "--uuid", file.absolutePath))
        fun encoded(values: Set<Pair<String, String>>) = JsonArray(values.sortedWith(compareBy({ it.first }, { it.second })).map {
            JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) })
        val dsymDirectory = archive.get().asFile.resolve("dSYMs")
        val dsymPaths = dsymDirectory.listFiles()?.filter { it.isDirectory && it.extension == "dSYM" }?.sortedBy { it.name }.orEmpty()
        require(dsymPaths.isNotEmpty()) { "Missing archive dSYM bundles" }
        val symbolUuids = dsymPaths.map(::uuids)
        val hashes = dsymPaths.map { path -> path.resolve("Contents/Resources/DWARF").listFiles()?.filter(File::isFile)?.associate { it.name to sha256(it) }
            .orEmpty().also { require(it.isNotEmpty()) { "Missing DWARF files" } } }
        val ipaHash = sha256(ipa.get().asFile)
        val uploaderHash = sha256(uploader.get().asFile)
        require(uploader.get().asFile.canExecute()) { "Crashlytics uploader is not executable" }
        try {
            ZipFile(ipa.get().asFile).use { zip ->
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
                require(info.value("CFBundleIdentifier") == bundleId.get()) { "Packaged bundle ID does not match release target" }
                require(info.value("CFBundleShortVersionString") == versionName.get() && info.value("CFBundleVersion") == buildNumber.get().toString()) { "Packaged version does not match release plan" }
                val google = extract("$app/GoogleService-Info.plist")
                val firebase = plist(google)
                require(firebase.value("BUNDLE_ID") == bundleId.get() && firebase.value("GOOGLE_APP_ID") == plist(firebaseConfig.get().asFile).value("GOOGLE_APP_ID")) { "Packaged Firebase app does not match release target" }
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
                val config = packagedFirebaseConfig.get().asFile.apply { parentFile.mkdirs(); google.copyTo(this, overwrite = true) }
                Files.setPosixFilePermissions(config.toPath(), PosixFilePermissions.fromString("rw-------"))
                hashes.forEachIndexed { index, files -> files.forEach { (name, hash) ->
                    require(sha256(dsymPaths[index].resolve("Contents/Resources/DWARF/$name")) == hash) { "dSYM changed during qualification" }
                } }
                val symbols = matching.map { index -> buildJsonObject {
                    put("path", dsymPaths[index].absolutePath); put("uuids", encoded(symbolUuids[index]))
                    put("dwarfSha256", buildJsonObject { hashes[index].forEach { (name, hash) -> put(name, hash) } }) } }
                require(ipaHash == sha256(ipa.get().asFile) && uploaderHash == sha256(uploader.get().asFile)) { "Release artifacts changed during qualification" }
                receipt.get().asFile.apply { parentFile.mkdirs(); writeText(buildJsonObject {
                    put("version", 1); put("ipaSha256", ipaHash); put("appBundleId", bundleId.get()); put("firebaseAppId", firebase.value("GOOGLE_APP_ID"))
                    put("appVersion", versionName.get()); put("appBuild", buildNumber.get()); put("packagedBinaries", JsonArray(binaries)); put("symbols", JsonArray(symbols))
                    put("uploaderSha256", uploaderHash); put("firebaseConfigSha256", sha256(config)); put("firebaseConfigPath", config.absolutePath)
                    put("providerUploadAccepted", false); put("serverSymbolProcessing", "Unknown"); put("physicalCrashFrames", "Not qualified")
                }.toString() + "\n") }
            }
        } finally {
            require(directory.deleteTreeSafely(within = directory.parentFile)) { "Could not remove owned archive-check scratch" }
        }
    }
}
