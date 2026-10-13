package dev.shibasis.dependeasy.versions

import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.toolchain.ToolchainVersions

object ServerLibraries {
    const val Quartz = "org.quartz-scheduler:quartz:${Versions.Quartz}"
    const val WebFlux = "org.springframework.boot:spring-boot-starter-webflux:${Versions.SDK.SpringBoot}"
    const val SpringBootBom = "org.springframework.boot:spring-boot-dependencies:${Versions.SDK.SpringBoot}"
    const val SpringDevtools = "org.springframework.boot:spring-boot-devtools"
    const val SpringProcessor = "org.springframework.boot:spring-boot-configuration-processor"
    const val SpringTest = "org.springframework:spring-test"
    const val JUnitLauncher = "org.junit.platform:junit-platform-launcher"
    const val GrafanaProvider = "2.30.0"
    const val GraphQL = "com.graphql-java:graphql-java:26.0"
    const val Hikari = "com.zaxxer:HikariCP:7.1.0"
    const val OAuth = "org.springframework.boot:spring-boot-starter-oauth2-resource-server:${Versions.SDK.SpringBoot}"
    const val Pulumi = "com.pulumi:pulumi:1.0.0"
    const val PulumiKubernetes = "com.pulumi:kubernetes:4.27.0"
    const val ReactorKotlin = "io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.3"
    const val Security = "org.springframework.boot:spring-boot-starter-security:${Versions.SDK.SpringBoot}"
}
