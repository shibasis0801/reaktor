import dev.shibasis.dependeasy.Versions
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins { id("dev.shibasis.dependeasy.library") }
dependeasy {
    module("dev.shibasis.reaktor.web") {
        common {
            dependencies {
                api(project(":reaktor-graph-runtime"))
            }
        }

        android {
            dependencies { api(Versions.Android.WebView) }
            integrationTestDependencies {
                implementation(Versions.Android.TestRunner)
                implementation(Versions.Android.TestJUnit)
            }
        }
        apple {}
        web {}
        jvm {}
    }

    desktopNative("desktopWebViewNative") {
        target = "ReaktorWebView"
        define("CMAKE_OSX_DEPLOYMENT_TARGET", "${Versions.SDK.targetDarwin}.0")
        define("REAKTOR_WEBVIEW_WINDOWS_SDK", Versions.WebView.WindowsSDK)
        define("REAKTOR_WEBVIEW_COMMIT", Versions.WebView.commit)
        define("REAKTOR_WEBVIEW_SHA256", Versions.WebView.archiveSha256)
    }
    val bridge = javascript("webBridge")
    val compileBridge = bridge.script("build", "js/dist")
    val testBridge = bridge.check().also { it.configure { dependsOn(compileBridge) } }
    tasks.register("installWebBridge") { dependsOn(bridge.install) }
    tasks.register("compileWebBridge") { dependsOn(compileBridge) }
    tasks.register("testWebBridge") { dependsOn(testBridge) }
    kotlinTemplate("WebBridgeAssets") {
        namespace = "dev.shibasis.reaktor.web"
        from(compileBridge)
        base64("entry", "js/dist/browser-entry.js")
        base64("client", "js/dist/index.js")
    }
    jvmClasspath("jvmDesktopWebViewProbeClasspath", "reports/webview/probe-classpath.txt")
    jvmEntryPoint("desktopWebViewProbe", "dev.shibasis.reaktor.web.DesktopWebViewProbeKt", compilation = "test") {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    if (providers.gradleProperty("reaktor.web.deviceProbe").orNull == "true") {
        (kotlin.targets.getByName("iosArm64") as KotlinNativeTarget).apply {
            binaries.framework("webViewProbe", listOf(NativeBuildType.DEBUG)) {
                baseName = "ReaktorWebProbe"
                compilation = compilations.getByName("test")
                isStatic = true
            }
        }
    }

    // These compilations contain callable device/browser probe fixtures, not unit test methods.
    // Their real qualification entrypoints are the host probes and testWebBridge.
    tasks.withType<org.gradle.api.tasks.testing.AbstractTestTask>().configureEach {
        if (name in setOf("jsNodeTest", "testDebugUnitTest", "testReleaseUnitTest")) {
            failOnNoDiscoveredTests.set(false)
        }
    }
}
