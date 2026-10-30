package dev.shibasis.dependeasy

import dev.shibasis.dependeasy.model.TestDiscovery
import dev.shibasis.dependeasy.model.TestEnvironmentSpec
import dev.shibasis.dependeasy.verification.jvmTestEnvironment
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.testfixtures.ProjectBuilder
import kotlin.test.Test as Case
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentTestsTest {
    @Case fun workerFailuresContainMessagesAndSourceFramesWithoutStandardStreams() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.register("verify", Test::class.java).get()
        task.jvmTestEnvironment(TestEnvironmentSpec())
        assertEquals(TestExceptionFormat.FULL, task.testLogging.exceptionFormat)
        assertFalse(task.testLogging.showStandardStreams)
        assertFalse(task.isScanForTestClasses)
        assertEquals(setOf("**/*Test.class", "**/*Tests.class"), task.includes)
        task.jvmTestEnvironment(TestEnvironmentSpec(discovery = TestDiscovery.Annotations, standardStreams = true))
        assertEquals(TestExceptionFormat.FULL, task.testLogging.exceptionFormat)
        assertTrue(task.isScanForTestClasses)
        assertTrue(task.testLogging.showStandardStreams)
    }
}
