package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.toolchain.buildHost
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.HostTestBuilder
import dev.shibasis.dependeasy.handles.publishModuleOutput
import dev.shibasis.dependeasy.configuration.resolve
import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.web.kotlinNodeTests
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal fun Project.componentTests(suite: Suite, settings: TestEnvironment, run: Run) {
    val environment = resolveTestEnvironment(settings)
    val spec = KernelSpec.TestSuite(suite, environment, run)
    fun Task.configureEnvironment() {
        testEnvironment(environment, run)
        extensions.add(KernelSpec.TestSuite::class.java, "dependeasyTestSuite", spec)
    }
    val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java)
    when (suite) {
        Suite.Hangar -> error("Hangar suites belong to desktop components")
        Suite.Jvm -> {
            require(settings.preloads.paths.isEmpty()) { "Node preloads belong to JavaScript suites" }
            require(settings.results == null) { "Process suites own result directories" }
            require(settings.caseTimeoutMillis == null) { "Per-case timeouts belong to JavaScript suites" }
            val execution = jvmExecution(KotlinCompilation.TEST_COMPILATION_NAME)
            val task = if (kotlin != null) {
                val target = kotlin.targets.getByName("jvm") as KotlinJvmTarget
                target.testRuns.getByName(KotlinCompilation.TEST_COMPILATION_NAME).executionTask
            } else tasks.named<Test>(JavaPlugin.TEST_TASK_NAME)
            val multiplatform = kotlin != null
            task.configure {
                configureEnvironment()
                jvmTestEnvironment(environment)
                if (multiplatform) desktopGestureAccess() else exclude("**/*\$*")
                settings.classpathProperties.forEach { classpathProperty(it, execution.classpath) }
            }
        }
        Suite.AndroidUnit -> {
            require(settings.preloads.paths.isEmpty() && settings.results == null && settings.caseTimeoutMillis == null) {
                "Android unit suites use JVM test settings"
            }
            val reports = files()
            extensions.getByType<LibraryAndroidComponentsExtension>().onVariants { variant ->
                variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.let { unit ->
                    unit.configureTestTask { task ->
                        task.configureEnvironment()
                        task.jvmTestEnvironment(environment)
                        settings.classpathProperties.forEach { task.classpathProperty(it, task.classpath) }
                    }
                    afterEvaluate {
                        val task = tasks.named<Test>(variant.computeTaskName("test", HostTestBuilder.UNIT_TEST_TYPE))
                        reports.from(task.map { it.reports.junitXml.outputLocation.get() })
                        reports.builtBy(task)
                    }
                }
            }
            publishModuleOutput(OutputKind.VerificationResults, reports, Verification.AndroidUnit)
        }
        Suite.JavaScript -> {
            require(settings.discovery == TestDiscovery.Conventional && !settings.standardStreams) { "Class discovery and standard streams belong to JVM suites" }
            require(settings.classpathProperties.isEmpty()) { "Classpath properties belong to JVM suites" }
            require(settings.properties.isEmpty() && settings.state == null && settings.results == null && settings.directories.isEmpty()) { "JVM properties belong to JVM suites" }
            if (settings.preloads.paths.isNotEmpty()) kotlinNodeTests(*settings.preloads.paths.map(rootProject::file).toTypedArray())
            requireNotNull(kotlin).js {
                nodejs {
                    testTask {
                        configureEnvironment()
                        settings.caseTimeoutMillis?.let { millis ->
                            useMocha { timeout = if (millis % 1000L == 0L) "${millis / 1000}s" else "${millis}ms" }
                        }
                    }
                }
            }
        }
    }
}

internal fun Project.resolveTestEnvironment(settings: TestEnvironment) = TestEnvironmentSpec(
    settings.classpathProperties.toSet(), settings.inputs.toList(), settings.caseTimeoutMillis,
    settings.properties.mapNotNull { resolve(it).orNull?.evidence }, settings.files,
    settings.state?.let { resolve(it).get().evidence },
    settings.results?.let { resolve(it).get().evidence },
    settings.preloads,
    settings.directories.groupBy { it.root }.mapValues { (_, directories) ->
        directories.map { resolve(it.property).get().evidence }
    },
    settings.discovery,
    settings.standardStreams,
    settings.allowEmpty,
    settings.inputs.associate { input -> input.name to resolve(BuildOption.environment(input.environment, input.default)).get().evidence },
)

internal fun Task.testEnvironment(settings: TestEnvironmentSpec, run: Run) {
    if (this is AbstractTestTask) failOnNoDiscoveredTests.set(!settings.allowEmpty)
    settings.inputs.forEach { input ->
        val value = settings.resolvedInputs.getValue(input.name).value
        inputs.property(input.name, value)
        if (input.rerunWhen == value) outputs.upToDateWhen { false }
    }
    if (settings.files.paths.isNotEmpty()) {
        inputs.files(settings.files.paths.map(project.rootProject::file)).withPropertyName("testEnvironmentFiles")
            .withPathSensitivity(PathSensitivity.RELATIVE).optional()
    }
    run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
}

internal fun Test.jvmTestEnvironment(settings: TestEnvironmentSpec) {
    setScanForTestClasses(settings.discovery == TestDiscovery.Annotations)
    if (settings.discovery == TestDiscovery.Conventional) include("**/*Test.class", "**/*Tests.class")
    testLogging.showStandardStreams = settings.standardStreams
    val home = if (TestDirectoryRoot.HostHome in settings.directories)
        project.option(BuildOption.systemProperty("user.home")).get().let { java.io.File(it) } else null
    TestEnvironmentKernel.plan(settings, project.layout.buildDirectory.get().asFile, project.projectDir, home)
        .forEach { (name, value) -> systemProperty(name, value) }
    settings.state?.let { state ->
        isolatedDirectory(state.name, project.layout.buildDirectory.dir(state.value),
            create = settings.directories[TestDirectoryRoot.State].orEmpty().map { it.value })
    }
}

internal fun Test.desktopGestureAccess() {
    if (project.buildHost.mac) {
        jvmArgs("--add-opens=java.desktop/com.apple.eawt.event=ALL-UNNAMED")
    }
}
