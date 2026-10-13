plugins { `kotlin-dsl` }

repositories { gradlePluginPortal(); google(); mavenCentral() }

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    sourceSets.main {
        kotlin.srcDir("../toolchain/src/main/kotlin")
        kotlin.srcDir("../gradle/src/main/kotlin")
        kotlin.include("dev/shibasis/dependeasy/bootstrap/**", "dev/shibasis/dependeasy/toolchain/GradlePlugins.kt",
            "dev/shibasis/dependeasy/toolchain/BuildLibraries.kt",
            "dev/shibasis/dependeasy/publishing/PublicationDefaults.kt")
    }
}
java { targetCompatibility = JavaVersion.VERSION_21 }

dependencies {
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

gradlePlugin {
    plugins.create("bytecode") {
        id = "dev.shibasis.dependeasy.bytecode"
        implementationClass = "dev.shibasis.dependeasy.bootstrap.BytecodeVerificationPlugin"
    }
}

tasks.test { useJUnitPlatform() }
