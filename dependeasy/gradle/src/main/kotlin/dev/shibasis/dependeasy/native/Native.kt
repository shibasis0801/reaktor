package dev.shibasis.dependeasy.native

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.handles.Tools
import dev.shibasis.dependeasy.model.ToolHandle
import dev.shibasis.dependeasy.toolchain.NativeSources
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import java.io.Serializable

enum class NativePlatform { Android, Darwin, Host }
enum class NativeAbi(val androidName: String) {
    Arm32("armeabi-v7a"), Arm64("arm64-v8a"), X86("x86"), X86_64("x86_64");
    companion object {
        val defaults get() = Versions.architectures.map { name -> entries.single { it.androidName == name } }
    }
}

data class NativeDependency(
    val name: String,
    val repository: String,
    val revision: String,
    val checksum: String,
    val checksumAlgorithm: String,
    val recipe: String,
    val source: ToolHandle? = null,
    val includeRoots: List<String> = emptyList(),
    val prefab: AndroidPrefabConfiguration? = null,
    val hostRoots: List<String> = emptyList(),
) : Serializable

object Native {
    val Flatbuffers = NativeDependency("flatbuffers", NativeSources.flatbuffers.first, NativeSources.flatbuffers.second,
        NativeSources.flatbuffers.second, "git-commit-sha1", "headers", Tools.Flatbuffers, listOf("include"))
    val Hermes = NativeDependency("hermes", NativeSources.hermes.first, NativeSources.hermes.second,
        NativeSources.hermes.second, "git-commit-sha1", "hermes", Tools.Hermes, listOf("API", "API/jsi", "public"),
        AndroidPrefabConfiguration("HERMES_PREFAB_DIR", "com.facebook.react:hermes-android:${ToolchainVersions.HermesAndroid}", "hermesvm",
            runtimeExcludes = listOf("com.facebook.fbjni" to "fbjni"), target = "hermes-engine::hermesvm",
            checksum = "408bbe7be526d0b1a80e10a09893fdcf5f4a456864464ae3456c3bb871374ab7"))
    val Fbjni = NativeDependency("fbjni", "maven:${Versions.Native.Fbjni}", Versions.Native.Fbjni.substringAfterLast(':'),
        "2927d8a99d1bce1844677b7d8e6f1d0f33670cfd154c30559fd26e6d155ceb1d", "sha256", "prefab",
        prefab = AndroidPrefabConfiguration("FBJNI_PREFAB_DIR", Versions.Native.Fbjni, "fbjni", Versions.Native.FbjniJava,
            target = "fbjni::fbjni", checksum = "2927d8a99d1bce1844677b7d8e6f1d0f33670cfd154c30559fd26e6d155ceb1d"))
    val OpenSslCrypto = NativeDependency("openssl-crypto", "maven:${Versions.Native.OpenSsl}", Versions.Native.OpenSsl.substringAfterLast(':'),
        "a5b05c4b362d35c022238ef9b2e4e2196248adea3bac9dd683845ee75a3a8d66", "sha256", "prefab", hostRoots = listOf("OPENSSL_ROOT_DIR"),
        prefab = AndroidPrefabConfiguration("OPENSSL_CRYPTO_PREFAB_DIR", Versions.Native.OpenSsl, "crypto", target = "OpenSSL::Crypto",
            checksum = "a5b05c4b362d35c022238ef9b2e4e2196248adea3bac9dd683845ee75a3a8d66",
            artifactVariables = listOf("OPENSSL_CRYPTO_LIBRARY"), includeVariables = listOf("OPENSSL_INCLUDE_DIR"),
            linkVariables = listOf("OPENSSL_LIBRARIES")))
    val OpenSslSsl = OpenSslCrypto.copy(name = "openssl-ssl", prefab = OpenSslCrypto.prefab!!.copy(
        cmakeVariable = "OPENSSL_SSL_PREFAB_DIR", moduleName = "ssl", target = "OpenSSL::SSL",
        links = listOf(OpenSslCrypto.prefab!!.target), artifactVariables = listOf("OPENSSL_SSL_LIBRARY"), includeVariables = emptyList()))
    val Foundation = NativeDependency("Foundation", "Apple SDK/Foundation.framework", "sdk", "sdk-metadata", "sha256", "foundation")
    val sources = listOf(Flatbuffers, Hermes)
}
