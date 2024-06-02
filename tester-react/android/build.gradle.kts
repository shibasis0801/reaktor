buildscript {
    val ndkVersion by extra( "25.0.8775105")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral {
            content {
                excludeGroup("com.facebook.react")
            }
        }
        maven(url = "$rootDir/../node_modules/react-native/android")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.21")
        classpath("org.jetbrains.kotlin:kotlin-serialization:1.9.21")
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("app.cash.sqldelight:gradle-plugin:2.0.0-alpha04")
    }
}

group = "dev.shibasis.flatinvoker.react"
version = "1.0-SNAPSHOT"


allprojects {
    repositories {
        maven(url = "$rootDir/../node_modules/react-native/android")
        mavenCentral {
            content {
                excludeGroup("com.facebook.react")
            }
        }
        google()
        maven(url = "https://www.jitpack.io")
    }
    afterEvaluate {
        project.extensions.findByType<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension>()?.let { ext ->
            ext.sourceSets.removeAll { sourceSet ->
                setOf(
                    "androidAndroidTestRelease",
                    "androidTestFixtures",
                    "androidTestFixturesDebug",
                    "androidTestFixturesRelease",
                ).contains(sourceSet.name)
            }
        }
    }
    tasks.withType<JavaExec>().configureEach {
        val nodeModulesLocation = project.findProperty("NODE_MODULES") as String? ?: "node_modules"
        environment("NODE_MODULES", nodeModulesLocation)
    }
}
