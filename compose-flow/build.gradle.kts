import dev.shibasis.dependeasy.android.*
import dev.shibasis.dependeasy.common.*
import dev.shibasis.dependeasy.darwin.*
import dev.shibasis.dependeasy.server.*
import dev.shibasis.dependeasy.web.*
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins {
    id("dev.shibasis.dependeasy.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    common {
        dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
        }
    }

    web {
        dependencies {
            kotlinWrappers()
            react()
            webCoroutines()
        }
        packageJson = file("ts/package.json")
    }

    droid {
        dependencies {
            activityFragment()
            androidCoroutines()
            extensions()
        }
    }

    darwin {
        dependencies {
            api("org.jetbrains.kotlinx:atomicfu:0.23.1")
        }
    }

    server {
        dependencies {
            api(compose.desktop.currentOs)
        }
    }
}

android {
    defaults("dev.shibasis.composeflow")
}

val parityMatrixFile = project.file("parity/features.json")
val parityReportDir = layout.buildDirectory.dir("reports/compose-flow")

tasks.register("reportParity") {
    group = "verification"
    description = "Generate compose-flow React Flow parity reports."
    inputs.file(parityMatrixFile)
    outputs.dir(parityReportDir)

    doLast {
        val matrix = JsonSlurper().parse(parityMatrixFile) as Map<*, *>
        val reportsDir = parityReportDir.get().asFile.apply { mkdirs() }
        val jsonFile = reportsDir.resolve("parity.json")
        val markdownFile = reportsDir.resolve("parity.md")
        jsonFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(matrix)))

        val categories = matrix["categories"] as List<Map<String, *>>
        val markdown = buildString {
            appendLine("# Compose Flow Parity Report")
            appendLine()
            appendLine("Generated from `parity/features.json`.")
            appendLine()
            categories.forEach { category ->
                appendLine("## ${category["name"]}")
                appendLine()
                appendLine("| Feature | Status | Notes |")
                appendLine("| --- | --- | --- |")
                val features = category["features"] as List<Map<String, *>>
                features.forEach { feature ->
                    val notes = (feature["notes"] as? String).orEmpty().replace("|", "\\|")
                    appendLine("| ${feature["name"]} | ${feature["status"]} | $notes |")
                }
                appendLine()
            }
        }
        markdownFile.writeText(markdown)
    }
}
