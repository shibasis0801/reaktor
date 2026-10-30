import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines
import java.net.URI
import java.security.MessageDigest

plugins { id("dev.shibasis.dependeasy.library") }


val interopRevision = "2034fe72e0c805a683ba76165660c834f2a8142a"
val interopDirectory = layout.buildDirectory.dir("interop-vectors")
val interopHashes = mapOf(
    "tree-math.json" to "27f04891f56106593b74b674445f01b845e173953770c084deb2f8cf5592e2fc",
    "crypto-basics.json" to "17cfcf89af9f51d0f2aa7af77f6f9ec99376a039214b6d42a6f11646b83e8c29",
    "secret-tree.json" to "08f92e6272452e2c832e32d38e16cf0c4aa28967e47d3842c60bc354c6b67a94",
    "message-protection.json" to "f7c1ae62ce63c3003e526d539c99f2b1444f65ee0a487d3a5667d46867526c45",
    "key-schedule.json" to "05aa9a68bd2538ace72d8c53375984cc728ef62220ebf314df675708546d97a7",
    "psk_secret.json" to "2b534969dba0b65a04b7d790496af5c0ccdb472b3fc4ca25c8c82df3e8523784",
    "tree-validation.json" to "b66a432d93b520623f0640835ed43b9dfc95e116b8be081d81581d7c11e67a37",
    "transcript-hashes.json" to "58046b58fbd98519b4e60953a105f550e4b427e2b140ea67dedcb751260eecb8",
    "welcome.json" to "06be9d5c99817ef2545e4b15b8e73fd9b604685a8e55b59ca168eda98e236502",
    "tree-operations.json" to "9a25f8720714d1256ba2dad8d83a660ba0bae0d33ed15c682b9d74a5d67aad0a",
    "treekem.json" to "d8bbdf78394797f21db7476fd286af6830ebcb18d2e3b2c839fecd4eb11056f8",
    "messages.json" to "b194abe1561995223482dbad51c180146920dc2f637e74d01e07a388308791fb",
    "passive-client-welcome.json" to "92ebb04b67b1aca4290965ae363650921842ceebe275bd79a2733414078d849c",
    "passive-client-handling-commit.json" to "b24949fdf857bf79d511f57504a8bdc7ce3247eb199684745e439c11b1a1da21",
    "passive-client-random.json" to "0095d863e9d316872e237fc708debcd20ba2687ee905a3e831f97dd3e578d3c1"
)
val prepareInteropVectors = tasks.register("prepareInteropVectors") {
    val revision = interopRevision
    val hashes = interopHashes
    val outputDirectory = interopDirectory
    inputs.property("revision", interopRevision)
    inputs.properties(interopHashes)
    outputs.dir(interopDirectory)
    doLast {
        val directory = outputDirectory.get().asFile.apply { mkdirs() }
        hashes.forEach { (name, expected) ->
            val destination = directory.resolve(name)
            fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!destination.isFile || digest(destination.readBytes()) != expected) {
                val connection = URI("https://raw.githubusercontent.com/mlswg/mls-implementations/$revision/test-vectors/$name").toURL().openConnection()
                connection.connectTimeout = 10000
                connection.readTimeout = 30000
                val bytes = connection.getInputStream().use { it.readNBytes(8 * 1024 * 1024 + 1) }
                require(bytes.size <= 8 * 1024 * 1024 && digest(bytes) == expected) { "MLS reference vector checksum mismatch: $name" }
                destination.writeBytes(bytes)
            }
        }
    }
}
tasks.matching { it.name == "nativeHostCheck" }.configureEach {
    dependsOn(prepareInteropVectors)
    inputs.dir(interopDirectory)
}

tasks.withType<Test>().configureEach {
    if (name == "jvmTest") {
        dependsOn("nativeHostCheck")
        systemProperty("jna.library.path", layout.buildDirectory.dir("dependeasy/native/host").get().asFile.absolutePath)
    }
}

library(namespace = "dev.shibasis.reaktor.security", targets = Targets.Android + Targets.Apple + Targets.Jvm + Targets.Web) {
    native(name = "ReaktorSecurity", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)) {
        val json = NativeContract.Project("cpp/external/json", "nlohmann_json::nlohmann_json",
            mapOf("JSON_BuildTests" to "OFF", "JSON_Install" to "OFF"), "nlohmann_json_DIR")
        val boringSsl = NativeContract.Project("cpp/external/boringssl", "crypto",
            mapOf("BUILD_TESTING" to "OFF", "BUILD_SHARED_LIBS" to "OFF"))
        val mls = NativeContract.Project("cpp/external/mlspp", "mlspp",
            mapOf("TESTING" to "OFF", "DISABLE_PQ" to "ON", "REQUIRE_BORINGSSL" to "ON",
                "CMAKE_MODULE_PATH" to project.file("cpp/cmake").absolutePath,
                  "CMAKE_PROJECT_mlspp_INCLUDE_BEFORE" to project.file("cpp/cmake/ApplyMlsSnapshot.cmake").absolutePath))
        val googleTest = NativeContract.Project("cpp/external/googletest", "GTest::gtest_main",
            mapOf("INSTALL_GTEST" to "OFF", "gtest_force_shared_crt" to "ON"), hostOnly = true)
        linkage(NativePlatform.Host, NativeLinkage.Shared)
        links(boringSsl)
        includeDirs("src/commonMain/cpp", "src/commonMain/cpp/include", "src/androidMain/cpp")
        uses(json)
        links(mls)
        tests("rsec_tests", listOf("src/commonTest/cpp/MlsEngineTest.cpp", "src/commonTest/cpp/RsecCapiTest.cpp",
              "src/commonTest/cpp/MlsSnapshotTest.cpp", "src/commonTest/cpp/MlsDurabilityTest.cpp",
                "src/commonTest/cpp/AttachmentTest.cpp", "src/commonTest/cpp/CredentialTest.cpp",
                  "src/commonTest/cpp/RsecDurabilityCapiTest.cpp", "src/commonTest/cpp/SealedKeyRingTest.cpp",
                    "src/commonTest/cpp/MlsInteropTest.cpp", "cpp/external/mlspp/cmd/interop/src/json_details.cpp"),
              googleTest, links = listOf("mls_vectors", "nlohmann_json::nlohmann_json"))
    }
    val generateBindings by bindings()
    val buildTypeScript by typescript(directory = "ts", configuration = "tsconfig.json", output = "dist", kotlin = listOf(kotlinJsLibrary(Reaktor.Security)))
    appleInterop(packageName = "dev.shibasis.reaktor.security.native", headers = listOf("src/commonMain/cpp/include/rsec/rsec.h"))
    dependencies {
        common { commonCoroutines(); api(Reaktor.Core, Reaktor.Service, Reaktor.GraphRuntime) }
        jvm { implementation(Versions.Native.Jna); implementation(Reaktor.Auth) }
        jvmTest { implementation(Reaktor.Auth) }
        web { api(Versions.Kotlin.KarakumRuntime); api(Reaktor.Cloudflare) }
        processors(Reaktor.Compiler)
    }
}

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
}

