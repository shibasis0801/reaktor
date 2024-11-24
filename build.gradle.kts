import com.android.build.gradle.internal.crash.afterEvaluate
import groovy.lang.Closure
import java.time.LocalDate

plugins {
    kotlin("multiplatform") apply false
    kotlin("android") apply false
    id("com.android.application") apply false
    id("com.android.library") apply false
    id("com.google.firebase.crashlytics") apply false
    id("org.jetbrains.compose") apply false
    id("org.jetbrains.kotlin.plugin.compose").apply(false)
    id("com.google.devtools.ksp") apply false
    id("dev.shibasis.dependeasy.library") apply false
    id("dev.shibasis.publisheasy") apply false
    id("dev.shibasis.dependeasy.application") apply false
    id("org.jetbrains.kotlinx.benchmark") apply false
    id("com.codingfeline.buildkonfig") apply false
    // applied
    id("org.jetbrains.dokka") version "1.9.20"
}

buildscript {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

allprojects {
    group = "dev.shibasis"
    version = LocalDate.now().run { "$year.$monthValue.$dayOfMonth" }

    repositories {
        mavenLocal()
        mavenCentral()
        google()
        maven(url = "https://www.jitpack.io")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        maven("https://oss.sonatype.org/content/repositories/snapshots")
        maven("https://maven.pkg.jetbrains.space/kotlin/p/wasm/experimental")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/shibasis0801/flatInvoker")
                credentials {
                    username = System.getenv("USERNAME") ?: ""
                    password = System.getenv("TOKEN") ?: ""
                }
            }
        }
        if (project.name != "dependeasy") {
            githubPublication(name)
        }
    }
}

tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class).configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xopt-in=kotlin.experimental.ExperimentalNativeApi")
    }
}

tasks.dokkaHtmlMultiModule.configure {
    outputDirectory.set(file("docs"))
}

rootProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin> {
    rootProject.the<org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension>().download = false
}


fun Project.githubPublication(id: String = name) {
    project.extensions.getByType(PublishingExtension::class.java).apply {
        publications.create(id.replace("-", ""), MavenPublication::class.java).apply {
            groupId = "dev.shibasis"
            artifactId = id
            version = project.version.toString()
            if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform"))
                from(project.components["kotlin"])
            else
                from(project.components["java"])


            when {
                project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform") ->
                    from(project.components["kotlin"])
                project.plugins.hasPlugin("java") ->
                    from(project.components["java"])
                else -> {
                    throw GradleException("Unsupported project type for publishing")
                }
            }
        }
    }
}
