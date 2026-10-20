package dev.shibasis.dependeasy.web

import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Synchronizes compiler-owned metadata and generated workspace membership in place")
abstract class KotlinWorkspaceMetadata : WebContractTask() {
    init { outputs.upToDateWhen { false } }
}
