package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString

object GradlePlanCache {
    private fun cache(directory: File, request: String, context: String) =
        directory.resolve("cache/${BuildModelReader.digest(request + context)}.json")

    fun load(directory: File, invocation: ValidatedGradleInvocation, contextDigest: String): Boolean {
        val file = cache(directory, invocation.requestDigest, contextDigest)
        if (!file.isFile) return false
        val plan = BuildModelReader.readPlan(file)
        require(plan.modelDigest == invocation.modelDigest && plan.requestDigest == invocation.requestDigest && plan.contextDigest == contextDigest)
        directory.resolve("${invocation.requestDigest}.json").writeText(file.readText())
        return true
    }

    fun store(directory: File, plan: GradlePlan) {
        val text = BuildModelReader.json.encodeToString(plan) + "\n"
        cache(directory, plan.requestDigest, plan.contextDigest).apply { parentFile.mkdirs(); writeText(text) }
        val output = directory.resolve("${plan.requestDigest}.json")
        output.writeText(text)
        BuildModelReader.readPlan(output)
    }
}
