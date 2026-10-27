package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.VerificationTools
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

private val ignoredSources = arrayOf("**/node_modules/**", "**/build/**", "**/dist/**", "**/.wrangler/**",
    "**/.git/**", "**/.gradle/**", "**/.kotlin/**", "**/test-results/**", "**/playwright-report/**")

private fun Project.declaredSources(inputs: List<FileInput>) = inputs.map { input ->
    when (input.kind) {
        FileInputKind.File -> file(input.identity)
        FileInputKind.Tree -> fileTree(input.identity) {
            include(input.includes)
            exclude(input.excludes)
            exclude(*ignoredSources)
        }
        else -> error("Test sources must be files or trees")
    }
}

internal fun Project.nodeSuite(name: String, files: FileSet, run: Run, optionalFiles: List<String> = emptyList(),
    sources: List<FileInput> = emptyList(), live: Boolean = false): NodeHandle {
    require(files.paths.isNotEmpty()) { "Node tests need declared test paths" }
    val packages = dev.shibasis.dependeasy.web.PnpmWorkspace.get(this)
    val directories = files.paths.map { file(it).parentFile }.distinct()
    val task = tasks.register<NodeTestSuite>(name) {
        environmentFiles.from(optionalFiles.map { path ->
            val file = file(path)
            fileTree(file.parentFile).matching { include(file.name) }
        })
        if (live || optionalFiles.isNotEmpty()) outputs.upToDateWhen { false }
        group = "verification"
        workingDirectory.set(layout.projectDirectory)
        executable.set(packages.tools.pnpm.map { it.asFile.absolutePath })
        environment.putAll(packages.tools.environment)
        toolVersion.set("pnpm ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm} / node ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node}")
        arguments.set(Pnpm.tests(files.paths))
        executionEffect.set(Effect.Verify)
        executionPlatform.set(run.host.platform)
        sourceFiles.from(file("package.json"), file("pnpm-lock.yaml"), directories.map { directory ->
            fileTree(directory) {
                exclude(*ignoredSources)
            }
        }, declaredSources(sources))
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
        environment.putAll(suite.environment)
        toolVersion.set("pnpm ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm} / node ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node}")
        configuration.set(config)
        policy.set(BuildModelReader.json.encodeToString(Playwright.serializer(), suite))
        sourceFiles.from(fileTree(config.asFile.parentFile) { exclude(*ignoredSources) },
            fileTree("tests/support"), file("package.json"), file("pnpm-lock.yaml"), declaredSources(suite.sources))
        receiptFile.set(layout.buildDirectory.file("dependeasy/checks/$name.properties"))
        dependsOn(packages.install)
        executionPlatform.set(run.host.platform)
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this).reference(this, task, OutputKind.VerificationResults, host = run.host)
    return node(task, OutputKind.VerificationResults).after(rootProject.node(packages.install))
}

internal fun Project.lighthouseAudit(name: String, audit: Lighthouse, artifacts: List<OutputHandle>, run: Run): NodeHandle {
    val packages = dev.shibasis.dependeasy.web.PnpmWorkspace.get(this)
    val page = audit.page
    val published = if (page is Lighthouse.Page.Bundle) {
        require(artifacts.size == 1 && artifacts.single().kind == OutputKind.BrowserBundle) { "A local Lighthouse audit needs one published browser bundle" }
        require(!java.io.File(page.directory).isAbsolute && ".." !in page.directory.split('/')) { "Lighthouse needs a directory within its bundle" }
        outputFiles(artifacts.single())
    } else null
    val policy = audit.copy(report = file(audit.report).absolutePath, page = when (page) {
        is Lighthouse.Page.Remote -> page
        is Lighthouse.Page.Bundle -> page.copy(server = file(page.server).absolutePath)
    })
    val task = tasks.register<LighthouseAudit>(name) {
        group = "verification"
        workingDirectory.set(layout.projectDirectory)
        executable.set(packages.tools.pnpm.map { it.asFile.absolutePath })
        environment.putAll(packages.tools.environment)
        toolVersion.set("pnpm ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Pnpm} / node ${dev.shibasis.dependeasy.toolchain.ToolchainVersions.Node}")
        this.policy.set(BuildModelReader.json.encodeToString(Lighthouse.serializer(), policy))
        sourceFiles.from(file("package.json"), file("pnpm-lock.yaml"), published ?: emptyList<Any>())
        if (page is Lighthouse.Page.Bundle) sourceFiles.from(fileTree(file(page.server).parentFile) { include("*.mjs") })
        outputFiles.from(file(audit.report))
        receiptFile.set(layout.buildDirectory.file("dependeasy/checks/$name.properties"))
        dependsOn(packages.install)
        executionPlatform.set(run.host.platform)
        run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
    }
    val workspace = dev.shibasis.dependeasy.workspace.BuildWorkspace.get(this)
    if (published != null) afterEvaluate { task.configure { bundleDirectory.set(published.singleFile) } }
    workspace.reference(this, task, OutputKind.VerificationResults, host = run.host)
    return node(task, OutputKind.VerificationResults).after(rootProject.node(packages.install)).also { node ->
        artifacts.forEach { workspace.consume(node, it) }
    }
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
