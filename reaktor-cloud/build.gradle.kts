import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File

// reaktor-cloud: the cloud control-plane library (commonMain model + jvmMain tool runners) PLUS
// the observability Pulumi program (jvmMain). One dependeasy module — common + server (jvm).
// android/ios targets stay empty (no code); commonMain is JS-ready (add web {} when needed).
plugins {
    id("dev.shibasis.dependeasy.library")
}

// --- Grafana provider Java SDK ---------------------------------------------------------------
// pulumiverse/grafana ships no Java SDK; generate it at build time (gitignored) and compile it to
// a local jar the jvm source set depends on. Requires the `pulumi` CLI on PATH (Dagger/CI provide).
val grafanaVersion = "2.30.0"
val grafanaGen = layout.buildDirectory.dir("grafana-sdk")
val grafanaClasses = layout.buildDirectory.dir("grafana-sdk-classes")

val grafanaSdkClasspath: Configuration by configurations.creating
dependencies {
    grafanaSdkClasspath("com.pulumi:pulumi:1.0.0")
    grafanaSdkClasspath("com.google.code.gson:gson:2.8.9")
    grafanaSdkClasspath("com.google.code.findbugs:jsr305:3.0.2")
}

val genGrafanaSdk by tasks.registering(Exec::class) {
    val outDir = grafanaGen.get().asFile
    outputs.dir(outDir)
    onlyIf { !File(outDir, "java/src/main/java/com/pulumi/grafana/Provider.java").exists() }
    commandLine(
        "sh", "-c",
        "pulumi plugin install resource grafana $grafanaVersion --server github://api.github.com/pulumiverse >/dev/null 2>&1 || true; " +
            "pulumi package gen-sdk grafana --language java -o '${outDir.absolutePath}'",
    )
    doLast {
        val resDir = File(outDir, "resources/com/pulumi/grafana").apply { mkdirs() }
        File(resDir, "version.txt").writeText(grafanaVersion)
        File(resDir, "plugin.json").writeText(
            """{"resource":true,"name":"grafana","server":"github://api.github.com/pulumiverse","version":"$grafanaVersion"}""",
        )
    }
}

val compileGrafanaSdk by tasks.registering(JavaCompile::class) {
    dependsOn(genGrafanaSdk)
    source(grafanaGen.map { it.dir("java/src/main/java") })
    classpath = grafanaSdkClasspath
    destinationDirectory.set(grafanaClasses)
    options.release.set(21)
    options.isFork = true
    options.forkOptions.jvmArgs = listOf("-Xmx4g")
    options.encoding = "UTF-8"
}

val grafanaSdkJar by tasks.registering(Jar::class) {
    dependsOn(compileGrafanaSdk, genGrafanaSdk)
    archiveBaseName.set("grafana-sdk")
    from(grafanaClasses)
    from(grafanaGen.map { it.dir("resources") })
}

kotlin {
    common {
        dependencies {
            api(project(":reaktor-core"))
            api(project(":reaktor-graph"))
        }
    }
    droid {}
    darwin {}
    server {
        dependencies {
            implementation("com.pulumi:pulumi:1.0.0")
            implementation("com.pulumi:kubernetes:4.27.0")
            implementation("com.google.code.gson:gson:2.8.9")
            implementation("com.google.code.findbugs:jsr305:3.0.2")
            implementation("org.yaml:snakeyaml:2.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.cloud")
}

// Generated Grafana SDK jar on the jvm (server) classpath — used only by the observability program.
dependencies {
    "jvmMainImplementation"(files(grafanaSdkJar))
}

// Pulumi's java runtime runs `gradle run`; expose the observability program (jvmMain) as `run`.
// Both JavaExec tasks run JVM-25 bytecode (the module's jvmTarget), so use a matching launcher.
val jvmLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the observability Pulumi program (used by `pulumi up`)."
    mainClass.set("dev.shibasis.reaktor.cloud.observability.MainKt")
    javaLauncher.set(jvmLauncher)
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
}

// Dev check: dump the Cloud inventory read from a repo's wrangler.json (real data, no creds).
//   ./gradlew :reaktor-cloud:verifyWrangler -PwranglerPath=/abs/path/to/repo
tasks.register<JavaExec>("verifyWrangler") {
    group = "verification"
    description = "Dump the Cloud inventory read from a repo's wrangler.json."
    mainClass.set("dev.shibasis.reaktor.cloud.WranglerVerifyKt")
    javaLauncher.set(jvmLauncher)
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    args((project.findProperty("wranglerPath") as String?) ?: rootDir.absolutePath)
}
