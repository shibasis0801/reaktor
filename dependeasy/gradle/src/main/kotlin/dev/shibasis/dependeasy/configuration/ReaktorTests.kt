package dev.shibasis.dependeasy.configuration

import dev.shibasis.dependeasy.model.TestEnvironment
import dev.shibasis.dependeasy.model.BuildOption
import dev.shibasis.dependeasy.model.OptionType
import dev.shibasis.dependeasy.model.OptionSource
import dev.shibasis.dependeasy.model.TestInput
import dev.shibasis.dependeasy.model.TestDiscovery

object ReaktorTests {
    val Headless = TestEnvironment(properties = listOf(
        BuildOption("java.awt.headless", OptionType.Boolean, "true", resolutionOrder = listOf(OptionSource.Default)),
    ))
    val Conductor = TestEnvironment(
        classpathProperties = setOf("reaktor.conductor.testClasspath"),
        inputs = listOf(
            TestInput("serviceTest", "REAKTOR_AGENT_SERVICE_TEST", "0"),
            TestInput("nativeActivityTest", "REAKTOR_NATIVE_ACTIVITY_TEST", "0"),
        ),
    )
    val Cloudflare = TestEnvironment(caseTimeoutMillis = 60_000)
    val Benchmarks = TestEnvironment(discovery = TestDiscovery.Annotations, standardStreams = true)
}
