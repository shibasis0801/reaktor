package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.DarwinArchiveKernel
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class DarwinArchiveTest {
    @TempDir lateinit var directory: File
    private val app = "11111111-1111-1111-1111-111111111111" to "arm64"
    private val kotlin = "22222222-2222-2222-2222-222222222222" to "arm64"
    private val extension = "33333333-3333-3333-3333-333333333333" to "arm64"
    @Test fun `app Kotlin and extension UUIDs select only matching archive symbols`() {
        assertEquals(listOf(0, 1, 2), DarwinArchiveKernel.requireMatchingSymbols(listOf(setOf(app), setOf(kotlin), setOf(extension)),
            listOf(setOf(app), setOf(kotlin), setOf(extension), setOf("other" to "arm64"))))
    }
    @Test fun `missing Kotlin symbols and mismatched UUID or architecture block qualification`() {
        for (symbols in listOf(listOf(setOf(app), setOf(extension)), listOf(setOf(app), setOf(kotlin.first to "arm64e"), setOf(extension)),
            listOf(setOf(app), setOf("other" to "arm64"), setOf(extension)))) {
            assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.requireMatchingSymbols(listOf(setOf(app), setOf(kotlin), setOf(extension)), symbols) }
        }
    }
    @Test fun `empty non device or unreadable UUIDs fail`() {
        for (text in listOf("not a UUID", "UUID: malformed (arm64) binary")) assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.symbolUuids(text) }
        assertEquals(setOf(app), DarwinArchiveKernel.symbolUuids("UUID: ${app.first.lowercase()} (arm64) fixture\n"))
        assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.requireMatchingSymbols(listOf(emptySet()), listOf(setOf(app))) }
        assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.requireMatchingSymbols(listOf(setOf(app.first to "x86_64")), listOf(setOf(app.first to "x86_64"))) }
    }
    @Test fun `privacy requires a nonblank purpose string for linked sensitive APIs`() {
        assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.checkPrivacy("AVCaptureDevice".toByteArray(), JsonObject(emptyMap())) }
        assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.checkPrivacy("AVCaptureDevice".toByteArray(), buildJsonObject { put("NSCameraUsageDescription", "") }) }
        DarwinArchiveKernel.checkPrivacy("AVCaptureDevice".toByteArray(), buildJsonObject { put("NSCameraUsageDescription", "Take a photo") })
        DarwinArchiveKernel.checkPrivacy("ordinary binary".toByteArray(), JsonObject(emptyMap()))
    }
    private fun uploadFixture(block: (JsonObject, JsonObject, File, File, File, File) -> Unit) {
        val ipa = directory.resolve("fixture.ipa").apply { writeText("packaged binary") }
        val uploader = directory.resolve("uploader").apply { writeText("provider executable") }
        val config = directory.resolve("GoogleService-Info.plist").apply { writeText("private-config-must-never-enter-receipt") }
        val bundle = directory.resolve("app.dSYM")
        val dwarf = bundle.resolve("Contents/Resources/DWARF/app").apply { parentFile.mkdirs(); writeText("symbols") }
        val evidence = buildJsonObject {
            put("ipaSha256", DarwinArchiveKernel.sha256(ipa)); put("uploaderSha256", DarwinArchiveKernel.sha256(uploader)); put("firebaseConfigPath", config.absolutePath)
            put("firebaseConfigSha256", DarwinArchiveKernel.sha256(config)); put("providerUploadAccepted", false)
            put("symbols", buildJsonArray { add(buildJsonObject {
                put("path", bundle.absolutePath); put("dwarfSha256", buildJsonObject { put("app", DarwinArchiveKernel.sha256(dwarf)) }) }) })
        }
        val result = buildJsonObject { put("succeeded", true); put("uploaded", buildJsonArray { add(bundle.absolutePath) }) }
        assertFalse(evidence.toString().contains(config.readText()))
        block(evidence, result, ipa, uploader, dwarf, config)
    }
    @Test fun `a complete provider upload verifies hashes before acceptance`() {
        uploadFixture { evidence, result, ipa, uploader, _, _ -> DarwinArchiveKernel.verifySymbolUpload(evidence, result, ipa, uploader) }
    }
    @Test fun `failed or incomplete provider upload cannot accept symbols`() {
        uploadFixture { evidence, result, ipa, uploader, _, _ ->
            assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.verifySymbolUpload(evidence, JsonObject(result + ("succeeded" to JsonPrimitive(false))), ipa, uploader) }
            assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.verifySymbolUpload(evidence, JsonObject(result + ("uploaded" to JsonArray(emptyList()))), ipa, uploader) }
        }
    }
    @Test fun `changes to IPA DWARF uploader or packaged config invalidate upload evidence`() {
        for (index in 0..3) uploadFixture { evidence, result, ipa, uploader, dwarf, config ->
            listOf(ipa, dwarf, uploader, config)[index].appendText("changed")
            assertFailsWith<IllegalArgumentException> { DarwinArchiveKernel.verifySymbolUpload(evidence, result, ipa, uploader) }
        }
    }
}
