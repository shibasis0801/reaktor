import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// reaktor-conductor: the outbound agent layer. Reaktor drives external harnesses (Claude Code,
// Codex) as replaceable runtimes over one canonical, Reaktor-owned conversation.
//
// commonMain carries the whole model — agents, thread, protocols, context compilation — with no
// project dependency at all, so the collaboration substrate is usable as a plain library and is
// testable without spawning a process. Only jvmMain knows what a subprocess is.
plugins {
    id("dev.shibasis.dependeasy.library")
}

kotlin {
    common {
        dependencies {
            commonCoroutines()
            commonSerialization(protobuf = false)
        }
    }

    web {}
    droid {}
    darwin {}
    server {
        dependencies {
            // SupervisedProcessExecutor: argv without a shell, independent stdout/stderr line
            // streams, process-tree ownership, timeouts, redaction, and plan fingerprints.
            api(project(":reaktor-tooling"))
        }
    }

    // Desktop and the standalone CLI consume this module on the repository's pinned Java 21.
    jvmToolchain(21)
    jvm().compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    defaults("dev.shibasis.reaktor.conductor")
}

// `./gradlew :reaktor-conductor:conduct --args="..."` — the terminal entry point.
tasks.register<JavaExec>("conduct") {
    group = "application"
    description = "Run a prompt through one agent, all agents, a council, or a pipeline."
    mainClass.set("dev.shibasis.reaktor.conductor.cli.ConductorCliKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
}

tasks.register("prepareAgentLauncher") {
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    dependsOn(jvmMain.compileTaskProvider)
    val launcher = layout.buildDirectory.file("agent-launcher")
    outputs.file(launcher)
    inputs.files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    doLast {
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        val java = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().executablePath.asFile.path
        val classpath = files(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles).asPath
        launcher.get().asFile.apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nexec ${quote(java)} -cp ${quote(classpath)} dev.shibasis.reaktor.conductor.cli.ConductorCliKt workspace \"\$@\"\n")
            setExecutable(true)
        }
    }
}

// A launcher that does not point into build/classes. `prepareAgentLauncher` is right for a test
// run; a harness entry that lives for weeks has to survive the next `clean`, so it gets its own
// copy: jars only, one directory, one script. Install with
//   workspace install --dir <workspace> --command <agent-dist>/bin/reaktor-agent
tasks.register<Sync>("agentDist") {
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    val distribution = layout.buildDirectory.dir("agent-dist")
    from(tasks.named("jvmJar")) { into("lib") }
    from(jvmMain.runtimeDependencyFiles.filter { it.name.endsWith(".jar") }) { into("lib") }
    into(distribution)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    preserve { include("bin/**") }
    val java = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.map { it.executablePath.asFile.path }
    doLast {
        distribution.get().file("bin/reaktor-agent").asFile.apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\nhere=\$(cd \"\$(dirname \"\$0\")/..\" && pwd)\n" +
                "exec '${java.get().replace("'", "'\"'\"'")}' -cp \"\$here/lib/*\" dev.shibasis.reaktor.conductor.cli.ConductorCliKt \"\$@\"\n")
            setExecutable(true)
        }
    }
}

tasks.named<Test>("jvmTest") {
    val compilation = kotlin.jvm().compilations.getByName("test")
    inputs.property("serviceTest", providers.environmentVariable("REAKTOR_AGENT_SERVICE_TEST").getOrElse("0"))
    inputs.property("nativeActivityTest", providers.environmentVariable("REAKTOR_NATIVE_ACTIVITY_TEST").getOrElse("0"))
    doFirst {
        systemProperty("reaktor.conductor.testClasspath", files(compilation.output.allOutputs, compilation.runtimeDependencyFiles).asPath)
    }
}
