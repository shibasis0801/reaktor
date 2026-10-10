package dev.shibasis.dependeasy.verification

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import org.gradle.api.tasks.testing.AbstractTestTask

/** Device installation and UI checks run on the UI host; builds never boot simulators. */
internal fun physicalDevicesOnly(project: Project) {
    val simulatorTarget = Regex("iosSimulatorArm64|iosX64|tvosSimulatorArm64|watchosSimulatorArm64", RegexOption.IGNORE_CASE)
    project.allprojects.forEach { child ->
        child.tasks.configureEach {
            if (simulatorTarget.containsMatchIn(name) && name.contains("Test", ignoreCase = true)) {
                enabled = false
                setDependsOn(emptyList<Any>())
            }
        }
        child.afterEvaluate {
            tasks.withType(KotlinTestReport::class.java).configureEach {
                // Registration of a lazy test task also registers it with KGP's report.
                // Realize those registrations before selecting this report's inputs.
                child.tasks.withType(AbstractTestTask::class.java).toList()
                fun activeTests(report: KotlinTestReport): List<AbstractTestTask> =
                    report.testTasks.filter { it.enabled } + report.children.flatMap { activeTests(it.get()) }
                testResults.setFrom(activeTests(this).distinct().map { it.binaryResultsDirectory })
            }
        }
    }
    project.gradle.projectsEvaluated {
        project.allprojects.forEach { child ->
            child.tasks.configureEach {
                if (simulatorTarget.containsMatchIn(name) && name.contains("Test", ignoreCase = true)) {
                    enabled = false
                    setDependsOn(emptyList<Any>())
                }
            }
        }
    }
}
