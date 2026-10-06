import dev.shibasis.dependeasy.web.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.Version
import dev.shibasis.dependeasy.tasks.KotlinCMakeTask
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.util.Base64
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("dev.shibasis.dependeasy.library")
}
kotlin {
    common {
        dependencies {
            api(project(":reaktor-graph-runtime"))
        }
    }
    droid {
        dependencies { api("androidx.webkit:webkit:${Version.WebView.AndroidX}") }
    }
    darwin {}
    web {}
    server {}
    sourceSets.named("androidInstrumentedTest") {
        dependencies {
            implementation("androidx.test:runner:${Version.WebView.AndroidTestRunner}")
            implementation("androidx.test.ext:junit:${Version.WebView.AndroidTestJUnit}")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.web")
    defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
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

val macHost = System.getProperty("os.name").startsWith("Mac")
val windowsHost = System.getProperty("os.name").startsWith("Windows")
val nativePlatform = (if (macHost) "macos-" else if (windowsHost) "windows-" else "linux-") + when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}
val nativeOutput = layout.buildDirectory.dir("libs/webview/$nativePlatform")
val jdk = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(Version.SDK.Java.asInt))
}
val desktopWebViewNative = tasks.register<KotlinCMakeTask>("desktopWebViewNative") {
    group = "build"
    description = "Build the host platform webview/webview C ABI and JNI adapter"
    sourceDirectory.set(layout.projectDirectory.dir("native/desktop"))
    sourceFiles.from(fileTree("native/desktop"))
    buildDirectory.set(layout.buildDirectory.dir("native/webview/$nativePlatform"))
    cmakeExecutable.set(listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake").firstOrNull { file(it).canExecute() } ?: "cmake")
    generator.set("Ninja")
    buildTarget.set("ReaktorWebView")
    configureArguments.set(jdk.map { launcher -> listOf(
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_DEPLOYMENT_TARGET=${Version.SDK.targetDarwin}.0",
        "-DREAKTOR_WEBVIEW_WINDOWS_SDK=${Version.WebView.WindowsSDK}",
        "-DJAVA_HOME=${launcher.metadata.installationPath.asFile}",
        "-DREAKTOR_WEBVIEW_COMMIT=${Version.WebView.commit}",
        "-DREAKTOR_WEBVIEW_SHA256=${Version.WebView.archiveSha256}",
        "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${nativeOutput.get().asFile}",
    ) })
    outputs.dir(nativeOutput)
}
val desktopWebViewResources = tasks.register<Copy>("desktopWebViewResources") {
    dependsOn(desktopWebViewNative)
    from(nativeOutput) { include("*.dylib", "*.so", "*.dll", "webview-LICENSE") }
    into(layout.buildDirectory.dir("generated/webview-resources/native/$nativePlatform"))
}
kotlin.sourceSets.named("jvmMain") {
    resources.srcDir(desktopWebViewResources.map { layout.buildDirectory.dir("generated/webview-resources").get() })
}

val desktopTarget = kotlin.targets.getByName("jvm") as KotlinJvmTarget
val probeCompilation = desktopTarget.compilations.getByName("test")
val probeClasspath = files(probeCompilation.output.allOutputs, probeCompilation.runtimeDependencyFiles)
tasks.register("jvmDesktopWebViewProbeClasspath") {
    dependsOn("jvmTestClasses")
    val output = layout.buildDirectory.file("reports/webview/probe-classpath.txt")
    outputs.file(output)
    inputs.files(probeClasspath)
    doLast {
        output.get().asFile.apply {
            parentFile.mkdirs()
            writeText(probeClasspath.files.joinToString("\n") { it.absolutePath })
        }
    }
}
tasks.register<JavaExec>("desktopWebViewProbe") {
    group = "verification"
    description = "Open the real macOS AWT embedding probe (run on the UI machine)"
    dependsOn("jvmTestClasses")
    classpath = probeClasspath
    javaLauncher.set(jdk)
    mainClass.set("dev.shibasis.reaktor.web.DesktopWebViewProbeKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val bridgeSource = layout.projectDirectory.dir("js")
val bridgeBuild = layout.buildDirectory.dir("web-bridge")
val prepareWebBridge = tasks.register<Copy>("prepareWebBridge") {
    from(bridgeSource) { exclude("node_modules/**", "dist/**", "package-lock.json") }
    into(bridgeBuild)
    inputs.property("typescript", Version.WebView.TypeScript)
    doLast {
        val manifest = bridgeBuild.get().file("package.json").asFile
        @Suppress("UNCHECKED_CAST")
        val data = groovy.json.JsonSlurper().parse(manifest) as MutableMap<String, Any>
        data["devDependencies"] = mapOf("typescript" to Version.WebView.TypeScript)
        manifest.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(data)))
    }
}
val installWebBridge = tasks.register<Exec>("installWebBridge") {
    dependsOn(prepareWebBridge)
    workingDir(bridgeBuild)
    commandLine("npm", "install", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.file(bridgeBuild.map { it.file("package.json") })
    outputs.dir(bridgeBuild.map { it.dir("node_modules") })
}
val compileWebBridge = tasks.register<Exec>("compileWebBridge") {
    dependsOn(installWebBridge)
    workingDir(bridgeBuild)
    commandLine("npm", "run", "build")
    inputs.dir(bridgeSource.dir("src"))
    inputs.file(bridgeSource.file("tsconfig.json"))
    outputs.dir(bridgeBuild.map { it.dir("dist") })
}
tasks.register<Exec>("testWebBridge") {
    group = "verification"
    dependsOn(compileWebBridge)
    workingDir(bridgeBuild)
    commandLine("npm", "test")
    inputs.dir(bridgeSource.dir("test"))
}
val generateWebBridgeAssets = tasks.register("generateWebBridgeAssets") {
    dependsOn(compileWebBridge)
    val output = layout.buildDirectory.dir("generated/web-bridge/kotlin")
    outputs.dir(output)
    inputs.dir(bridgeBuild.map { it.dir("dist") })
    doLast {
        val root = output.get().asFile.resolve("dev/shibasis/reaktor/web").apply { mkdirs() }
        val entry = Base64.getEncoder().encodeToString(bridgeBuild.get().file("dist/browser-entry.js").asFile.readBytes())
        val client = Base64.getEncoder().encodeToString(bridgeBuild.get().file("dist/index.js").asFile.readBytes())
        root.resolve("WebBridgeAssets.kt").writeText("""
            @file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            package dev.shibasis.reaktor.web
            import kotlin.io.encoding.Base64
            object WebBridgeAssets : WebAssetProvider {
                override val paths = setOf("/reaktor/bridge.js", "/reaktor/index.js")
                override fun read(path: String): WebAsset? = when (webAssetPath(path)) {
                    "/reaktor/bridge.js" -> WebAsset(Base64.decode("$entry"), "text/javascript")
                    "/reaktor/index.js" -> WebAsset(Base64.decode("$client"), "text/javascript")
                    else -> null
                }
            }
        """.trimIndent())
    }
}
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateWebBridgeAssets.map { layout.buildDirectory.dir("generated/web-bridge/kotlin").get() })
}
tasks.named("check") { dependsOn("testWebBridge") }
