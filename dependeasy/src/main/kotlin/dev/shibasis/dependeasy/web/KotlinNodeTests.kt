package dev.shibasis.dependeasy.web

import org.gradle.api.Project
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

/** Supply Compose's runtime beside Kotlin's Node test imports; no browser or simulator is launched. */
internal fun Project.kotlinNodeTests(vararg imports: Any) {
    val preloads = imports.map(::file)
    val runtime = provider {
        val skiko = configurations.getByName("jsTestCompileClasspath").files.single {
            it.name.startsWith("skiko-js-") && it.extension == "klib"
        }
        val version = skiko.name.removePrefix("skiko-js-").removeSuffix(".klib")
        zipTree(configurations.detachedConfiguration(dependencies.create("org.jetbrains.skiko:skiko-js-wasm-runtime:$version")).singleFile)
    }
    val prepare = tasks.register<Copy>("prepareKotlinNodeTestRuntime") {
        dependsOn("jsTestTestDevelopmentExecutableCompileSync")
        from(runtime)
        include("skiko.mjs", "skiko.wasm", "skikod8.mjs", "js-reexport-symbols.mjs")
        into(rootProject.layout.buildDirectory.dir("js/packages/${rootProject.name}-${project.name}-test/kotlin"))
    }
    tasks.withType(KotlinJsTest::class.java).matching { it.name == "jsNodeTest" }.configureEach {
        dependsOn(prepare)
        nodeJsArgs += preloads.flatMap { listOf("--import", it.absolutePath) }
        inputs.files(preloads)
    }
}
