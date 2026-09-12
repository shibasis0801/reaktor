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
