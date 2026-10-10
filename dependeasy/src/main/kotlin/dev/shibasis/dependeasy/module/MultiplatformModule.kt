package dev.shibasis.dependeasy.module

import com.android.build.api.dsl.LibraryExtension
import dev.shibasis.dependeasy.android.AndroidConfiguration
import dev.shibasis.dependeasy.android.defaults
import dev.shibasis.dependeasy.android.droid
import dev.shibasis.dependeasy.common.CommonConfiguration
import dev.shibasis.dependeasy.common.common
import dev.shibasis.dependeasy.darwin.DarwinConfigure
import dev.shibasis.dependeasy.darwin.appleFramework
import dev.shibasis.dependeasy.darwin.darwin
import dev.shibasis.dependeasy.server.ServerConfiguration
import dev.shibasis.dependeasy.server.server
import dev.shibasis.dependeasy.web.WebConfiguration
import dev.shibasis.dependeasy.web.web
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.jetbrains.compose.ComposePlugin
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

class MultiplatformModule internal constructor(project: Project, namespace: String) {
    private val kotlin = project.extensions.getByType<KotlinMultiplatformExtension>()

    val compose: ComposePlugin.Dependencies
        get() = (kotlin as ExtensionAware).extensions.getByType(ComposePlugin.Dependencies::class.java)

    init {
        project.extensions.getByType<LibraryExtension>().defaults(namespace)
    }

    fun common(configure: CommonConfiguration.() -> Unit = {}) = kotlin.common(configure)
    fun web(configure: WebConfiguration.() -> Unit = {}) = kotlin.web(configure)
    fun android(configure: AndroidConfiguration.() -> Unit = {}) = kotlin.droid(configure)
    fun apple(configure: DarwinConfigure.() -> Unit = {}) = kotlin.darwin(configure)
    fun jvm(configure: ServerConfiguration.() -> Unit = {}) = kotlin.server(configure)

    fun framework(name: String = "app", exports: List<Any> = emptyList(), transitiveExport: Boolean = false) =
        kotlin.appleFramework(name, exports, transitiveExport)

    fun kotlin(configure: KotlinMultiplatformExtension.() -> Unit) = kotlin.apply(configure)
}
