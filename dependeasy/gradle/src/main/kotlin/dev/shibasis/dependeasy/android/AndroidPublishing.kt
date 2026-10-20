package dev.shibasis.dependeasy.android

import dev.shibasis.dependeasy.configuration.FrameworkOptions
import dev.shibasis.dependeasy.configuration.option
import com.android.build.api.dsl.ApplicationExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.configureAndroidPublishing() {
    plugins.withId("com.google.firebase.crashlytics") {
        plugins.withId("com.android.application") {
            val upload = option(FrameworkOptions.PublishCrashlytics)
            extensions.configure<ApplicationExtension> {
                buildTypes.configureEach {
                    configure<CrashlyticsExtension> {
                        mappingFileUploadEnabled = upload.get()
                    }
                }
            }
        }
    }
}
