package dev.shibasis.dependeasy.android

import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.androidApplication(id: String, configuration: BaseAppModuleExtension.() -> Unit) {
    extensions.configure<BaseAppModuleExtension> {
        defaults(id)
        configuration()
    }
}
