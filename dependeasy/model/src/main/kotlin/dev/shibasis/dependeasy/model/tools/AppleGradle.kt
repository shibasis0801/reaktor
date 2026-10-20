package dev.shibasis.dependeasy.model.tools

import dev.shibasis.dependeasy.model.*

object AppleGradle {
    fun arguments(host: AppleHostContract, versionName: String, buildNumber: String, release: Boolean = false): List<String> =
        listOf(host.versionOption to versionName, host.numberOption to buildNumber)
            .filter { it.first.isNotBlank() }.map { "-P${it.first}=${it.second}" } +
            if (release && host.developmentToolsOption.isNotBlank()) listOf("-P${host.developmentToolsOption}=false") else emptyList()

    fun android(task: String, host: AppleHostContract, versionName: String, buildNumber: Int, release: Boolean): ReleaseCommand =
        ReleaseCommand(".", listOf("./gradlew", task) + arguments(host, versionName, buildNumber.toString()) +
            if (release) listOf("-Pdependeasy.publishCrashlytics=true", "-P${host.developmentToolsOption}=false", "--console=plain") else listOf("--console=plain"))

    fun deploy(node: NodeId, host: AppleHostContract, versionName: String, buildNumber: Int): ReleaseCommand =
        ReleaseCommand(".", listOf("./gradlew", node.project.trimEnd(':') + ":" + node.name,
            "-P${host.developmentToolsOption}=false") + arguments(host, versionName, buildNumber.toString()) + "--console=plain")

    fun verify(task: String, host: AppleHostContract, versionName: String, buildNumber: Int): ReleaseCommand =
        ReleaseCommand(".", listOf("./gradlew", task, "--console=plain") + arguments(host, versionName, buildNumber.toString()))
}
