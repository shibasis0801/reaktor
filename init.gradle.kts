// Global build cache configuration
gradle.settingsEvaluated {
    buildCache {
        local {
            directory = File(rootDir, "build-cache")
            removeUnusedEntriesAfterDays = 30
        }
    }
}

// Increase parallel compilation for all projects
allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xuse-k2",
                "-Xbackend-threads=8",
                "-opt-in=kotlin.experimental.ExperimentalNativeApi"
            )
        }
    }
    
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-Xbackend-threads=8"
            )
        }
    }
}