plugins { `kotlin-dsl` }

repositories { gradlePluginPortal(); google(); mavenCentral() }

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    sourceSets.main {
        kotlin.srcDir("../toolchain/src/main/kotlin")
        kotlin.srcDir("../toolchain/src/contracts/kotlin")
    }
}
java { targetCompatibility = JavaVersion.VERSION_21 }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.9.0")
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
