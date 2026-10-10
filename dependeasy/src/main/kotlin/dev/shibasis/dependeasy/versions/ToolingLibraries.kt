package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object ToolingLibraries {
    private const val GrpcVersion = "1.84.0"
    private const val ProtobufVersion = "4.36.1"
    private const val KotlinPoetVersion = "2.2.0"
    private const val McpVersion = "0.7.2"
    const val Protoc = "com.google.protobuf:protoc:$ProtobufVersion"
    const val GrpcCompiler = "io.grpc:protoc-gen-grpc-java:$GrpcVersion"
    const val JUnitLauncher = "org.junit.platform:junit-platform-launcher"
    const val CommonsCompress = "org.apache.commons:commons-compress:1.28.0"
    const val Adb = "com.android.tools.adblib:adblib:9.4.0"
    const val Clikt = "com.github.ajalt.clikt:clikt:5.0.3"
    const val GrpcOkHttp = "io.grpc:grpc-okhttp:$GrpcVersion"
    const val GrpcProtobuf = "io.grpc:grpc-protobuf:$GrpcVersion"
    const val GrpcStub = "io.grpc:grpc-stub:$GrpcVersion"
    const val JUnit4 = "junit:junit:4.13.2"
    const val JavaxAnnotations = "org.apache.tomcat:annotations-api:6.0.53"
    const val Jsr305 = "com.google.code.findbugs:jsr305:3.0.2"
    const val KotlinPoet = "com.squareup:kotlinpoet:$KotlinPoetVersion"
    const val KotlinPoetKsp = "com.squareup:kotlinpoet-ksp:$KotlinPoetVersion"
    const val KspApi = "com.google.devtools.ksp:symbol-processing-api:${ToolchainVersions.Ksp}"
    const val KubernetesClient = "io.kubernetes:client-java:27.0.0"
    const val Lsp = "org.eclipse.lsp4j:org.eclipse.lsp4j:0.23.1"
    const val McpClient = "io.modelcontextprotocol:kotlin-sdk-client:$McpVersion"
    const val McpServer = "io.modelcontextprotocol:kotlin-sdk-server:$McpVersion"
    const val Mordant = "com.github.ajalt.mordant:mordant:3.0.2"
    const val Protobuf = "com.google.protobuf:protobuf-java:$ProtobufVersion"
    const val SnakeYaml = "org.yaml:snakeyaml:${ToolchainVersions.SnakeYaml}"
}
