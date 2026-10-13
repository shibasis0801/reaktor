package dev.shibasis.dependeasy.android

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.androidApplication(id: String, configuration: ApplicationExtension.() -> Unit) {
    extensions.configure<ApplicationExtension> {
        defaults(id)
        configuration()
    }
}
