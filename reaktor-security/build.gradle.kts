import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.native.*
import dev.shibasis.dependeasy.native.NativePlatform
import dev.shibasis.dependeasy.model.Targets
import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.common.commonCoroutines

plugins { id("dev.shibasis.dependeasy.library") }

library(namespace = "dev.shibasis.reaktor.security", targets = Targets.Android + Targets.Apple + Targets.Jvm + Targets.Web) {
    native(name = "ReaktorSecurity", platforms = setOf(NativePlatform.Android, NativePlatform.Darwin, NativePlatform.Host),
        abis = listOf(NativeAbi.X86, NativeAbi.Arm64, NativeAbi.X86_64)) {
        val json = NativeContract.Project("cpp/external/json", "nlohmann_json::nlohmann_json",
            mapOf("JSON_BuildTests" to "OFF", "JSON_Install" to "OFF"), "nlohmann_json_DIR")
        val mls = NativeContract.Project("cpp/external/mlspp", "mlspp",
            mapOf("TESTING" to "OFF", "DISABLE_PQ" to "ON"))
        val googleTest = NativeContract.Project("cpp/external/googletest", "GTest::gtest_main",
            mapOf("INSTALL_GTEST" to "OFF", "gtest_force_shared_crt" to "ON"), hostOnly = true)
        links(Native.OpenSslCrypto, Native.OpenSslSsl)
        uses(json)
        links(mls)
        tests("rsec_tests", listOf("src/commonTest/cpp/MlsEngineTest.cpp", "src/commonTest/cpp/RsecCapiTest.cpp"), googleTest)
    }
    val generateBindings by bindings()
    appleInterop(packageName = "dev.shibasis.reaktor.security.native", headers = listOf("src/commonMain/cpp/include/rsec/rsec.h"))
    dependencies {
        common { commonCoroutines(); api(Reaktor.Core) }
        jvm { implementation(Versions.Native.Jna) }
        web { api(Versions.Kotlin.KarakumRuntime) }
        processors(Reaktor.Compiler)
    }
}
