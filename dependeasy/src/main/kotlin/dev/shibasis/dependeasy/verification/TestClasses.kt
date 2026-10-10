package dev.shibasis.dependeasy.verification

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

internal fun Project.testClasses(patterns: Array<out String>) {
    tasks.withType(Test::class.java).configureEach {
        setScanForTestClasses(false)
        include(*patterns.ifEmpty { arrayOf("**/*Test.class", "**/*Tests.class") })
    }
}
