import dev.shibasis.dependeasy.*
import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import org.gradle.api.tasks.Exec


plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

extra["kotlinx-serialization.version"] = "1.8.0"


kotlin {
    common {
        dependencies {
            api(project(":reaktor-ui"))
            api(project(":reaktor-graph"))
            api(project(":reaktor-service"))
            api(project(":reaktor-db"))
            api(project(":reaktor-io"))
            api("com.appstractive:jwt-kt:1.2.1")
        }
    }

    web {
        dependencies {

        }
    }

    droid {
        dependencies {
            implementation("androidx.credentials:credentials:1.3.0")
            // Android 13 and below.
            implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
            implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
        }
    }

    darwin {
        podDependencies {
            pod("GoogleSignIn", "8.0.0")
        }
    }

    server {
        dependencies {
            api("org.springframework.boot:spring-boot-starter-oauth2-resource-server:${Version.SDK.SpringBoot}")
            api("org.springframework.boot:spring-boot-starter-security:${Version.SDK.SpringBoot}")
            api("org.jetbrains.exposed:exposed-core:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-jdbc:${Version.Exposed}")
            api("org.jetbrains.exposed:exposed-json:${Version.Exposed}")
            api("org.postgresql:postgresql:42.7.3")
            api("org.jetbrains.kotlin:kotlin-reflect:${Version.SDK.Kotlin}")
        }
    }

    sourceSets {
        // JVM integration tests run against an ephemeral in-memory H2 (PostgreSQL mode) — the production
        // Supabase database is never touched. See AuthTestDb.
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation("com.h2database:h2:2.2.224")
        }
    }
}

android {
    defaults("dev.shibasis.reaktor.auth")
}

tasks.withType<Test> {
    // Shared in-memory DB harnesses, stubs, and Kotlin file facades are test support, not runnable tests.
    filter {
        excludeTestsMatching("*Fixture*")
        excludeTestsMatching("*Stub*")
        excludeTestsMatching("*Kt*")
    }
}

val normalizeGoogleSignInIosBuildSettings by tasks.registering {
    dependsOn("podBuildGoogleSignInIos")
    outputs.upToDateWhen { false }

    doLast {
        val settingsFile = layout.buildDirectory
            .file("cocoapods/buildSettings/build-settings-ios-GoogleSignIn.properties")
            .get()
            .asFile

        if (!settingsFile.exists()) return@doLast

        val normalized = settingsFile.readText()
            .replace("Debug-maccatalyst", "Debug-iphoneos")
            .replace(
                "GoogleSignIn.framework/Versions/A/Headers",
                "GoogleSignIn.framework/Headers"
            )

        settingsFile.writeText(normalized)
    }
}

val buildGoogleSignInIosSimulatorWithSdk by tasks.registering(Exec::class) {
    dependsOn("podSetupBuildGoogleSignInIosSimulator")
    workingDir = layout.buildDirectory.dir("cocoapods/synthetic/ios/Pods").get().asFile
    commandLine(
        "xcodebuild",
        "-project", "Pods.xcodeproj",
        "-scheme", "GoogleSignIn",
        "-sdk", "iphonesimulator",
        "-configuration", "Debug",
        "build",
    )
    outputs.dir(layout.buildDirectory.dir("cocoapods/synthetic/ios/build/Debug-iphonesimulator/GoogleSignIn"))
}

tasks.matching { it.name == "podBuildGoogleSignInIosSimulator" }.configureEach {
    dependsOn(buildGoogleSignInIosSimulatorWithSdk)
    onlyIf {
        // Xcode 26 resolves this generated pod scheme as Catalyst-only unless
        // the simulator SDK is explicit. The replacement task above supplies it.
        false
    }
}

tasks.matching { it.name == "cinteropGoogleSignInIosArm64" }.configureEach {
    dependsOn(normalizeGoogleSignInIosBuildSettings)
}
