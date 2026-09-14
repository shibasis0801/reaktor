import dev.shibasis.dependeasy.Version

plugins {
    kotlin("jvm")
}

/**
 * The idb companion's gRPC service, spoken directly.
 *
 * `idb_companion` is a gRPC server; the Python `idb` CLI is only one of its clients, and on a
 * machine where that client is missing — or installed somewhere a login shell cannot see — every
 * Apple per-target operation reports as unavailable. Talking to the companion removes that
 * dependency and, more importantly, makes the streaming surfaces reachable: video, HID and log
 * are streams the CLI can only hand back as files.
 *
 * A plain JVM module rather than a source set inside `reaktor-tooling`, because protobuf codegen
 * and Kotlin Multiplatform do not compose well, and this has no reason to be multiplatform.
 */
val protobufVersion = "4.36.1"
val grpcVersion = "1.84.0"

val protocTool: Configuration by configurations.creating
val grpcPluginTool: Configuration by configurations.creating

/** Apple silicon and Intel Macs, plus Linux for CI. Windows has no idb to talk to. */
val toolClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val platform = if (os.contains("mac")) "osx" else "linux"
    val cpu = if (arch == "aarch64" || arch == "arm64") "aarch_64" else "x86_64"
    "$platform-$cpu"
}

dependencies {
    // Executables, resolved from Maven so no machine needs protoc installed.
    protocTool("com.google.protobuf:protoc:$protobufVersion:$toolClassifier@exe")
    grpcPluginTool("io.grpc:protoc-gen-grpc-java:$grpcVersion:$toolClassifier@exe")

    api("com.google.protobuf:protobuf-java:$protobufVersion")
    api("io.grpc:grpc-protobuf:$grpcVersion")
    api("io.grpc:grpc-stub:$grpcVersion")
    // OkHttp transport rather than Netty: the companion is on loopback or a forwarded port, and
    // grpc-netty-shaded adds several megabytes for throughput nothing here needs.
    api("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${Version.Coroutines}")
    // `javax.annotation.Generated`, referenced by the generated stubs and absent from modern JDKs.
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    testImplementation(kotlin("test"))
}

val generatedProtoDir = layout.buildDirectory.dir("generated/proto")

val generateIdbProto by tasks.registering {
    description = "Generates the idb companion's protobuf and gRPC stubs."
    val protoDir = layout.projectDirectory.dir("src/main/proto")
    val outputDir = generatedProtoDir
    inputs.dir(protoDir)
    inputs.files(protocTool, grpcPluginTool)
    outputs.dir(outputDir)
    doLast {
        val protoc = protocTool.singleFile
        val grpcPlugin = grpcPluginTool.singleFile
        listOf(protoc, grpcPlugin).forEach { it.setExecutable(true) }
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        providers.exec {
            commandLine(
                protoc.absolutePath,
                "--plugin=protoc-gen-grpc-java=${grpcPlugin.absolutePath}",
                "--proto_path=${protoDir.asFile.absolutePath}",
                "--java_out=${target.absolutePath}",
                "--grpc-java_out=${target.absolutePath}",
                protoDir.file("idb.proto").asFile.absolutePath,
            )
        }.result.get().assertNormalExitValue()
    }
}

sourceSets.main {
    java.srcDir(generatedProtoDir)
}

tasks.named("compileJava") { dependsOn(generateIdbProto) }
tasks.named("compileKotlin") { dependsOn(generateIdbProto) }

kotlin {
    jvmToolchain(21)
}
