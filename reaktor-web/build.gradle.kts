import dev.shibasis.dependeasy.components.library
import dev.shibasis.dependeasy.codegen.KotlinTemplate
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.native.Native
import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.configuration.option

plugins { id("dev.shibasis.dependeasy.library") }
library(namespace = "dev.shibasis.reaktor.web", targets = Targets.All) {
    dependencies {
        common { api(projects.reaktorGraphRuntime) }
        android { api(Versions.Android.WebView) }
        androidInstrumentedTest {
            implementation(Versions.Android.TestRunner)
            implementation(Versions.Android.TestJUnit)
        }
    }
    val desktopWebViewNative by desktopNative(target = "ReaktorWebView", sources = listOf(Native.WebView), defines = mapOf(
        "CMAKE_OSX_DEPLOYMENT_TARGET" to Versions.SDK.targetMacos,
        "REAKTOR_WEBVIEW_WEBKITGTK_API" to Versions.WebView.WebKitGtkApi,
        "REAKTOR_WEBVIEW_WINDOWS_SDK" to Versions.WebView.WindowsSDK,
    ))
    // Callable probe fixtures are qualified through the host probes and testWebBridge.
    val browserFixtures by tests(Suite.JavaScript, environment = TestEnvironment(allowEmpty = true))
    val androidFixtures by tests(Suite.AndroidUnit, environment = TestEnvironment(
        discovery = TestDiscovery.Annotations, allowEmpty = true))
    val jvmDesktopWebViewProbeClasspath by classpath(output = "reports/webview/probe-classpath.txt")
    val desktopWebViewProbe by program(compilation = CompilationRef.Test,
        environment = ProgramEnvironment(nativeAccess = true), run = Run.onDesktopSession())
    val webBridge by javascript()
    val webBridgeBuild by typescript(configuration = "js/tsconfig.json", output = "js/dist")
    val testBridge = webBridge.tests(FileSet(listOf("js/test/*.mjs")), after = webBridgeBuild)
    val installWebBridge by artifacts(webBridge.installation)
    val compileWebBridge by artifacts(webBridgeBuild)
    val testWebBridge by verify(Verification.WebBridge, testBridge)
    if (project.option(FrameworkOptions.DeviceProbe).get()) {
        val webViewProbe by appleTestFramework(baseName = "ReaktorWebProbe")
    }
    val WebBridgeAssets by generated(KotlinTemplate(namespace = "dev.shibasis.reaktor.web", assets = mapOf(
        "entry" to "js/dist/browser-entry.js", "client" to "js/dist/index.js",
    )), from = webBridgeBuild)
}
