import dev.shibasis.dependeasy.Versions
import dev.shibasis.dependeasy.android.activityFragment
import dev.shibasis.dependeasy.android.androidCoroutines
import dev.shibasis.dependeasy.android.extensions

import dev.shibasis.dependeasy.web.webCoroutines
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

plugins { id("dev.shibasis.dependeasy.compose-library") }

dependeasy {
    module("dev.shibasis.composeflow") {
        common {
            dependencies {
                api(Versions.Compose.Runtime)
                api(Versions.Compose.Foundation)
                api(Versions.Compose.Material3)
            }
        }

        web {
            dependencies {
                webCoroutines()
            }
        }

        android {
            dependencies {
                activityFragment()
                androidCoroutines()
                extensions()
            }
        }

        apple {
            dependencies {
                api(Versions.Kotlin.AtomicFu)
            }
        }

        jvm {
            dependencies {
                api(Versions.Compose.Desktop)
            }
        }
    }


    tasks.withType<Test>().configureEach {
        systemProperty("java.awt.headless", "true")
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            // Exercise JDK native gesture dispatch and listener cleanup in the desktop regression.
            jvmArgs("--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
        }
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
}
