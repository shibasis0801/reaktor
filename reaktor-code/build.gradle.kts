import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import dev.shibasis.dependeasy.common.commonCoroutines
import dev.shibasis.dependeasy.Versions
import org.gradle.api.tasks.bundling.Zip

plugins { id("dev.shibasis.dependeasy.library") }

dependeasy {
    module("dev.shibasis.reaktor.code") {
        common { dependencies { commonCoroutines() } }
        web {}
        android {}
        apple {}

        jvm {
            dependencies {
                api(projects.reaktorGraphRuntime)
                implementation(dev.shibasis.dependeasy.Versions.Kotlin.CompilerEmbeddable)
                implementation(dev.shibasis.dependeasy.Versions.Kotlin.SerializationJson)
            }
        }
    }


    dependencyBoundary("verifyCodeBoundary") {
        description = "Keeps the editing contract usable by a renderer and a language server alike."
        forbidGroupPrefixes("androidx.compose", "org.jetbrains.compose", "org.jetbrains.skiko", "org.eclipse.lsp4j")
        forbidModules("reaktor-ui", "reaktor-tooling", "reaktor-graph", "engine")
        reason.set("Code contract picked up a renderer or a server")
    }
}

repositories.exclusiveContent {
    forRepository {
        repositories.maven("https://packages.jetbrains.team/maven/p/kt/dev") { name = "KotlinAnalysisStandalone" }
    }
    filter { includeVersionByRegex("org\\.jetbrains\\.kotlin", ".*", Regex.escape(Versions.Kotlin.AnalysisStandaloneVersion)) }
}

val kotlinAnalysisCompiler = configurations.create("kotlinAnalysisCompiler") { isCanBeConsumed = false }
val kotlinAnalysisRuntime = configurations.create("kotlinAnalysisRuntime") { isCanBeConsumed = false }
val kotlinAnalysisTestMetadata = configurations.create("kotlinAnalysisTestMetadata") { isCanBeConsumed = false; isTransitive = false }
dependencies {
    add(kotlinAnalysisCompiler.name, Versions.Kotlin.AnalysisCompiler)
    add(kotlinAnalysisRuntime.name, Versions.Kotlin.AnalysisStandalone)
    add(kotlinAnalysisRuntime.name, Versions.Kotlin.SerializationJsonJvm)
    add(kotlinAnalysisTestMetadata.name, "${Versions.Kotlin.Stdlib}:all")
}
val kotlinAnalysisClasses = layout.buildDirectory.dir("kotlin-analysis/classes")
val compileKotlinAnalysis = tasks.register<JavaExec>("compileKotlinAnalysis") {
    classpath = kotlinAnalysisCompiler
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    inputs.files(fileTree("tools/kotlin-analysis") { include("**/*.kt") }, kotlinAnalysisRuntime)
    outputs.dir(kotlinAnalysisClasses)
    doFirst {
        args = listOf("-no-stdlib", "-no-reflect", "-jvm-target", "25", "-classpath", kotlinAnalysisRuntime.asPath,
            "-d", kotlinAnalysisClasses.get().asFile.absolutePath) + inputs.files.filter { it.extension == "kt" }.map { it.absolutePath }
    }
}
val semanticDistribution = tasks.register<Zip>("kotlinSemanticDistZip") {
    dependsOn(compileKotlinAnalysis)
    archiveFileName.set("kotlin-semantics.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(kotlinAnalysisClasses) { into("classes") }
    from(kotlinAnalysisRuntime) { into("lib") }
    from("tools/kotlin-analysis/run")
}

val unitTests = extensions.getByType<KotlinMultiplatformExtension>().targets.withType(KotlinJvmTarget::class.java).single().testRuns.getByName(KotlinCompilation.TEST_COMPILATION_NAME).executionTask
unitTests.configure { exclude("**/KotlinAnalysisIntegrationTest*") }
tasks.register<Test>("kotlinSemanticTest") {
    dependsOn(semanticDistribution)
    val ordinary = unitTests.get()
    testClassesDirs = ordinary.testClassesDirs
    classpath = ordinary.classpath
    include("**/KotlinAnalysisIntegrationTest*")
    systemProperty("reaktor.kotlin.archive", layout.buildDirectory.file("distributions/kotlin-semantics.zip").get().asFile.absolutePath)
    doFirst {
        systemProperty("reaktor.kotlin.stdlib", classpath.files.single { it.name == "kotlin-stdlib-${Versions.SDK.Kotlin}.jar" }.absolutePath)
        systemProperty("reaktor.kotlin.commonStdlib", kotlinAnalysisTestMetadata.singleFile.absolutePath)
    }
}
