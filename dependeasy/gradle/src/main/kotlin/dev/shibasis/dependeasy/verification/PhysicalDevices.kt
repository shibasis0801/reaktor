package dev.shibasis.dependeasy.verification

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.testing.internal.KotlinTestReport
import org.gradle.api.tasks.testing.AbstractTestTask

/** Device installation and UI checks run on the UI host; builds never boot simulators. */
internal fun physicalDevicesOnly(project: Project) {
    project.allprojects.forEach { child ->
        child.tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach {
            enabled = false
            setDependsOn(emptyList<Any>())
        }
        child.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            child.extensions.getByType(KotlinMultiplatformExtension::class.java).targets
                .withType(KotlinNativeTargetWithSimulatorTests::class.java).configureEach {
                    compilations.configureEach {
                        if (name == KotlinCompilation.TEST_COMPILATION_NAME) compileTaskProvider.configure {
                            enabled = false
                            setDependsOn(emptyList<Any>())
                        }
                    }
                    binaries.configureEach {
                        if (compilation.name == KotlinCompilation.TEST_COMPILATION_NAME) linkTaskProvider.configure {
                            enabled = false
                            setDependsOn(emptyList<Any>())
                        }
                    }
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
            child.tasks.withType(KotlinNativeSimulatorTest::class.java).configureEach {
                enabled = false
                setDependsOn(emptyList<Any>())
            }
        }
    }
}
