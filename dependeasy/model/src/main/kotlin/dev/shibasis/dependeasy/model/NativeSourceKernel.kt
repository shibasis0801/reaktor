package dev.shibasis.dependeasy.model

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

object NativeSourceKernel {
    private val webviewHooks = listOf(
        Triple("cocoa_webkit.hh", "auto ui_delegate = create_webkit_ui_delegate();",
            "::reaktor_web_configure_apple(reinterpret_cast<void *>(config));\n    auto ui_delegate = create_webkit_ui_delegate();"),
        Triple("win32_edge.hh", "env->CreateCoreWebView2Controller(m_window, this)",
            "::reaktor_web_create_windows_controller(env, m_window, static_cast<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler *>(this))"),
        Triple("win32_edge.hh", "nullptr, userDataFolder, nullptr, m_com_handler",
            "nullptr, ::reaktor_web_windows_data_folder(), nullptr, m_com_handler"),
        Triple("win32_edge.hh", "args->put_State(COREWEBVIEW2_PERMISSION_STATE_ALLOW);",
            "args->put_State(COREWEBVIEW2_PERMISSION_STATE_DENY); // reaktor_default_deny"),
        Triple("gtk_webkitgtk.hh", "m_webview = webkit_web_view_new();",
            "m_webview = GTK_WIDGET(::reaktor_web_create_gtk());"),
    )

    fun prepare(source: NativeContract.Source) {
        require(source.recipe == "webview" && source.checksumAlgorithm == "sha256") { "Unsupported native archive recipe" }
        require(source.revision.matches(Regex("[a-f0-9]{40}")) && source.checksum.matches(Regex("[a-f0-9]{64}"))) { "Native source needs its immutable revision and SHA256" }
        val directory = File(source.directory).absoluteFile.normalize()
        directory.mkdirs()
        val backends = directory.resolve("core/include/webview/detail/backends")
        val identity = source.checksum + "\n" + webviewHooks.joinToString("\n")
        val receipt = directory.resolve(".prepared")
        if (receipt.isFile && receipt.readText() == identity && directory.resolve("CMakeLists.txt").isFile &&
            webviewHooks.all { (path, _, replacement) -> backends.resolve(path).takeIf(File::isFile)?.readText()?.contains(replacement) == true }) return
        val archive = Files.createTempFile(directory.toPath(), "source-", ".tgz")
        try {
            ToolInstallKernel.download(source.repository, archive) {}
            check(BuildModelReader.digest(archive.toFile()) == source.checksum) { "Native source checksum mismatch: ${source.name}" }
            val prefix = "${source.name}-${source.revision}/"
            TarArchiveInputStream(GZIPInputStream(Files.newInputStream(archive))).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    if (!entry.isFile || !entry.name.startsWith(prefix)) continue
                    val path = directory.toPath().resolve(entry.name.removePrefix(prefix)).normalize()
                    require(path.startsWith(directory.toPath()) && path != directory.toPath()) { "Invalid native source entry: ${entry.name}" }
                    Files.createDirectories(path.parent)
                    Files.newOutputStream(path).use(tar::copyTo)
                }
            }
            check(directory.resolve("CMakeLists.txt").isFile) { "Native archive has no build declaration: ${source.name}" }
            webviewHooks.forEach { (path, original, replacement) ->
                val file = backends.resolve(path)
                val text = file.readText()
                require(text.indexOf(original) >= 0 && text.indexOf(original) == text.lastIndexOf(original)) { "Pinned construction hook no longer matches: $path / $original" }
                file.writeText(text.replace(original, replacement))
            }
            receipt.writeText(identity)
        } finally { Files.deleteIfExists(archive) }
    }
}
