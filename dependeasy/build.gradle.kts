
plugins {
    id("java-gradle-plugin")
    id("maven-publish")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.sam.with.receiver")
}

fun kotlinConstants(path: String) = Regex("const val (\\w+) = (?:\"([^\"]+)\"|(\\d+))")
    .findAll(file(path).readText())
    .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
val toolchains = kotlinConstants("src/main/kotlin/dev/shibasis/dependeasy/toolchain/ToolchainVersions.kt")
val libraryVersions = kotlinConstants("src/main/kotlin/dev/shibasis/dependeasy/Versions.kt")
val toolingLibraries = kotlinConstants("src/main/kotlin/dev/shibasis/dependeasy/versions/ToolingLibraries.kt")
val publicationDefaults = kotlinConstants("src/main/kotlin/dev/shibasis/dependeasy/publishing/PublicationDefaults.kt")
val kotlinVersion = toolchains.getValue("Kotlin")

repositories {
    google()
    mavenCentral()
    maven(url = "https://plugins.gradle.org/m2/")
}

dependencies {
    implementation(gradleKotlinDsl())
    testImplementation(kotlin("test-junit5"))
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Align Version of all Kotlin components
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("com.android.tools.build:gradle:${toolchains.getValue("Agp")}") {
        exclude(group = "org.apache.commons", module = "commons-compress")
    }
    implementation(toolingLibraries.getValue("CommonsCompress"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-allopen:$kotlinVersion")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:${toolchains.getValue("SpringBoot")}")
    implementation("org.springframework.boot:spring-boot-buildpack-platform:${toolchains.getValue("SpringBoot")}")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
    implementation("org.jetbrains.compose:compose-gradle-plugin:${toolchains.getValue("Compose")}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${libraryVersions.getValue("Serialization")}")
    implementation("org.yaml:snakeyaml:${toolchains.getValue("SnakeYaml")}")
    implementation("com.google.firebase:firebase-crashlytics-gradle:${toolchains.getValue("Crashlytics")}")
    implementation("com.google.gms:google-services:${toolchains.getValue("GoogleServices")}")
    implementation("com.codingfeline.buildkonfig:buildkonfig-gradle-plugin:${toolchains.getValue("BuildKonfig")}")
    implementation("com.google.devtools.ksp:symbol-processing-gradle-plugin:${toolchains.getValue("Ksp")}")
    implementation("org.jetbrains.kotlinx:kotlinx-benchmark-plugin:${toolchains.getValue("KotlinBenchmark")}")

}

gradlePlugin {
    mapOf(
        "compose-library" to "ComposeLibraryPlugin",
        "compose-application" to "ComposeApplicationPlugin",
        "browser" to "BrowserPlugin",
        "compose-jvm" to "ComposeJvmPlugin",
        "jvm" to "JvmPlugin",
        "spring-application" to "SpringApplicationPlugin",
    ).forEach { (name, implementation) ->
        plugins.create(name) {
            id = "dev.shibasis.dependeasy.$name"
            implementationClass = "dev.shibasis.dependeasy.plugins.$implementation"
        }
    }
    plugins.create("pipeline") {
        id = "dev.shibasis.dependeasy.pipeline"
        implementationClass = "dev.shibasis.dependeasy.plugins.PipelinePlugin"
    }
    plugins.create("library") {
        id = "dev.shibasis.dependeasy.library"
        implementationClass = "dev.shibasis.dependeasy.plugins.LibraryPlugin"
    }

    plugins.create("application") {
        id = "dev.shibasis.dependeasy.application"
        implementationClass = "dev.shibasis.dependeasy.plugins.ApplicationPlugin"
    }

    plugins.create("settings") {
        id = "dev.shibasis.dependeasy.settings"
        implementationClass = "dev.shibasis.dependeasy.plugins.SettingsPlugin"
    }
}

samWithReceiver { annotation("org.gradle.api.HasImplicitReceiver") }
kotlin {
    jvmToolchain(toolchains.getValue("Java").toInt())
    // Gradle consumers may run on 21; the build compiler and product JVMs use 25.
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
java { targetCompatibility = JavaVersion.VERSION_21 }

tasks.test { useJUnitPlatform() }

tasks.named<Jar>("jar") {
    archiveFileName.set("dependeasy.jar")
}

// Bootstrap: this build cannot apply the plugin that it is compiling.
group = publicationDefaults.getValue("Group")
version = providers.gradleProperty("reaktorVersion").orElse(publicationDefaults.getValue("Version")).get()
publishing {
    repositories.maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/${publicationDefaults.getValue("Repository")}")
        credentials {
            username = providers.environmentVariable("USERNAME").orNull
            password = providers.environmentVariable("TOKEN").orNull
        }
    }
}
