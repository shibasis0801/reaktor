package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.VerificationTools
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.nodeSuite(name: String, files: FileSet, run: Run): NodeHandle {
    require(files.paths.isNotEmpty()) { "Node tests need declared test paths" }
    val packages = dev.shibasis.dependeasy.web.PnpmWorkspace.get(this)
    val sources = files.paths.map { file(it).parentFile }.distinct()
    val task = tasks.register<dev.shibasis.dependeasy.web.PackageCommand>(name) {
        group = "verification"
        workingDirectory.set(layout.projectDirectory)
        executable.set(packages.tools.pnpm.map { it.asFile.absolutePath })
        environment.putAll(packages.tools.environment)
        toolVersion.set("pnpm ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm} / node ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node}")
        arguments.set(Pnpm.tests(files.paths))
        executionEffect.set(Effect.Verify)
        executionPlatform.set(run.host.platform)
        sourceFiles.from(file("package.json"), file("pnpm-lock.yaml"), sources.map { directory ->
            fileTree(directory) {
                exclude("**/node_modules/**", "**/build/**", "**/dist/**", "**/.wrangler/**",
                    "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/test-results/**", "**/playwright-report/**")
            }
        })
        receiptFile.set(layout.buildDirectory.file("dependeasy/checks/$name.properties"))
        dependsOn(packages.install)
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, task, OutputKind.VerificationResults, host = run.host)
    return node(task, OutputKind.VerificationResults).after(rootProject.node(packages.install))
}

internal fun Project.browserSuite(name: String, suite: Playwright, run: Run): NodeHandle {
    val packages = dev.shibasis.dependeasy.web.PnpmWorkspace.get(this)
    val config = layout.projectDirectory.file(suite.configuration)
    val task = tasks.register<BrowserSuite>(name) {
        group = "verification"
        workingDirectory.set(layout.projectDirectory)
        executable.set(packages.tools.pnpm.map { it.asFile.absolutePath })
        environment.putAll(packages.tools.environment)
        toolVersion.set("pnpm ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm} / node ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node}")
        configuration.set(config)
        policy.set(BuildModelReader.json.encodeToString(Playwright.serializer(), suite))
        sourceFiles.from(fileTree(config.asFile.parentFile) { exclude("test-results/**", "playwright-report/**") },
            fileTree("tests/support"), file("package.json"), file("pnpm-lock.yaml"),
            suite.sources.map { input ->
                when (input.kind) {
                    FileInputKind.File -> file(input.identity)
                    FileInputKind.Tree -> fileTree(input.identity) {
                        include(input.includes)
                        exclude(input.excludes)
                        exclude("**/node_modules/**", "**/build/**", "**/dist/**", "**/.wrangler/**",
                            "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/test-results/**", "**/playwright-report/**")
                    }
                    else -> error("Browser test sources must be files or trees")
                }
            })
        receiptFile.set(layout.buildDirectory.file("dependeasy/checks/$name.properties"))
        dependsOn(packages.install)
        executionPlatform.set(run.host.platform)
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, task, OutputKind.VerificationResults, host = run.host)
    return node(task, OutputKind.VerificationResults).after(rootProject.node(packages.install))
}

internal fun Project.karateSuite(name: String, directory: String, config: String, tags: List<String>, files: List<String>, optionalFiles: List<String> = emptyList(), environment: Map<String, String> = emptyMap()): NodeHandle {
    val tool = VerificationTools.get(this).karate
    val executionEnvironment = environment.mapValues { (key, value) -> option(BuildOption.environment(key, value)) }
    val task = tasks.register<KarateSuite>(name) {
        group = "verification"
        workingDirectory.set(rootProject.layout.projectDirectory)
        executable.set(java.io.File(option(BuildOption.systemProperty("java.home")).get(), "bin/java").absolutePath)
        toolVersion.set(tool.handle.version)
        jar.set(tool.executable); dependsOn(tool.installation)
        suites.set(listOf(directory)); this.tags.set(tags)
        executionEnvironment.forEach { (key, value) -> this.environment.put(key, value) }
        arguments.set(listOf("--configdir", config))
        environmentFiles.from(optionalFiles.map { path ->
            val file = rootProject.file(path)
            rootProject.fileTree(file.parentFile).matching { include(file.name) }
        }, files.map(rootProject::file))
        sourceFiles.from(rootProject.fileTree(directory), rootProject.fileTree(config).matching { include("*.js", "*.json") })
        outputDirectories.from(layout.buildDirectory.dir("reports/karate/$name"))
    }
    return node(task, OutputKind.VerificationResults).after(rootProject.node(tool.installation))
}

internal fun Project.k6Suite(name: String, script: String, files: List<String>, optionalFiles: List<String> = emptyList(), environment: Map<String, String> = emptyMap()): NodeHandle {
    val tool = VerificationTools.get(this).k6
    val executionEnvironment = environment.mapValues { (key, value) -> option(BuildOption.environment(key, value)) }
    val task = tasks.register<K6Suite>(name) {
        group = "verification"
        workingDirectory.set(rootProject.layout.projectDirectory)
        executable.set(tool.executable.map { it.asFile.absolutePath }); dependsOn(tool.installation)
        toolVersion.set(tool.handle.version); this.script.set(rootProject.layout.projectDirectory.file(script))
        executionEnvironment.forEach { (key, value) -> this.environment.put(key, value) }
        environmentFiles.from(optionalFiles.map { path ->
            val file = rootProject.file(path)
            rootProject.fileTree(file.parentFile).matching { include(file.name) }
        }, files.map(rootProject::file))
        sourceFiles.from(rootProject.file(script))
        outputFiles.from(layout.buildDirectory.file("reports/k6/$name.json"))
    }
    return node(task, OutputKind.VerificationResults).after(rootProject.node(tool.installation))
}
