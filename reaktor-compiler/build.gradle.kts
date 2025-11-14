plugins {
    kotlin("jvm")
}

group = "dev.shibasis.reaktor"
version = "1.0-SNAPSHOT"


repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":reaktor-core"))
    implementation("com.squareup:kotlinpoet:2.2.0")
    implementation("com.squareup:kotlinpoet-ksp:2.2.0")
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.2")
}

sourceSets.main {
    kotlin.srcDirs("src/main/kotlin")
}
