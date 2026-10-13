package dev.shibasis.dependeasy.android

import com.android.build.gradle.AppExtension
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

internal fun Project.configureAndroidPublishing() {
    plugins.withId("com.google.firebase.crashlytics") {
        plugins.withId("com.android.application") {
            val upload = providers.gradleProperty("dependeasy.publishCrashlytics")
                .map(String::toBooleanStrict).orElse(false)
            extensions.configure<AppExtension> {
                buildTypes.configureEach {
                    configure<CrashlyticsExtension> {
                        mappingFileUploadEnabled = upload.get()
                    }
                }
            }
        }
    }
}
