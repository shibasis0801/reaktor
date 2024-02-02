plugins {
    kotlin("jvm")
}

group = "dev.reaktor"
version = "1.0-SNAPSHOT"


repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":reaktor-core"))
    implementation("com.squareup:kotlinpoet:1.14.2")
    implementation("com.squareup:kotlinpoet-ksp:1.14.2")
    implementation("com.google.devtools.ksp:symbol-processing-api:1.9.21-1.0.16")
}

sourceSets.main {
    kotlin.srcDirs("src/main/kotlin")
}
