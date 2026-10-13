package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import kotlinx.benchmark.gradle.BenchmarksExtension
import kotlinx.benchmark.gradle.JvmBenchmarkTarget
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.tasks.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal class JvmBenchmarkKernel(private val project: Project, private val workload: JvmBenchmark) {
    fun install() {
        project.pluginManager.apply("org.jetbrains.kotlinx.benchmark")
        val multiplatform = project.extensions.getByType<KotlinMultiplatformExtension>()
        multiplatform.targets.named("jvm", KotlinJvmTarget::class.java).configure {
            val main = compilations.getByName("main")
            compilations.create("benchmark") {
                associateWith(main)
                // The JMH generator accepts Java 21 bytecode; scored forks still run on Java 25.
                compileTaskProvider.configure { compilerOptions.jvmTarget.set(JvmTarget.JVM_21) }
                defaultSourceSet {
                    kotlin.srcDir("src/jvmBenchmark/kotlin")
                    dependencies { implementation(dev.shibasis.dependeasy.Versions.Kotlin.Benchmark) }
                }
            }
        }
        project.extensions.getByType<BenchmarksExtension>().apply {
            targets.register("jvmBenchmark") { (this as JvmBenchmarkTarget).jmhVersion = ToolchainVersions.Jmh }
            configurations.named("main").configure {
                iterations = workload.iterations; warmups = workload.warmups
                iterationTime = workload.iterationMillis; iterationTimeUnit = "ms"
                outputTimeUnit = workload.outputUnit; reportFormat = "json"
                include(workload.pattern); advanced("jvmForks", workload.forks)
            }
        }
        project.tasks.withType(Jar::class.java).configureEach {
            if (name == "jvmBenchmarkBenchmarkJar") exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        }
        allocations()
    }

    private fun allocations() {
        val report = project.layout.buildDirectory.file("reports/benchmarks/jvm-jmh-gc.json")
        val jar = project.layout.buildDirectory.dir("benchmarks/jvmBenchmark/jars").map { directory ->
            project.fileTree(directory).matching { include("*-JMH.jar") }.singleFile
        }
        project.tasks.register<JavaExec>(workload.allocationTask) {
            group = "benchmark"
            description = "Run JMH with its allocation profiler"
            dependsOn("jvmBenchmarkBenchmarkJar")
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(ToolchainVersions.Java))
            })
            classpath = project.files(jar)
            mainClass.set("org.openjdk.jmh.Main")
            jvmArgs("-Xms1g", "-Xmx1g")
            args(workload.pattern, "-f", workload.forks, "-wi", workload.warmups, "-i", workload.iterations,
                "-w", "${workload.iterationMillis}ms", "-r", "${workload.iterationMillis}ms",
                "-tu", workload.outputUnit, "-rf", "json", "-rff", report.get().asFile.absolutePath, "-prof", "gc")
            outputs.file(report)
            doFirst { report.get().asFile.parentFile.mkdirs() }
        }
    }
}
