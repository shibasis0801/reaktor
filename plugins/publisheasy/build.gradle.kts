val kotlinVersion = "2.0.21"

plugins {
    id("java-gradle-plugin")
    id("maven-publish")
//    https://plugins.gradle.org/docs/publish-plugin Do this
//    id("com.gradle.plugin-publish") version "1.2.1"
    `kotlin-dsl`
    kotlin("jvm")  version "2.0.21"
}

kotlin { jvmToolchain(11) }

repositories {
    google()
    mavenCentral()
    maven(url = "https://plugins.gradle.org/m2/")
    maven(url = "https://jitpack.io")

}

dependencies {
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
}

gradlePlugin {
    val publishEasy by plugins.creating {
        id = "dev.shibasis.publisheasy"
        implementationClass = "dev.shibasis.publisheasy.plugins.PublishEasy"
    }
}


