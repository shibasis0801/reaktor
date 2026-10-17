package dev.shibasis.dependeasy.dagger

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Checks the checked-in pipeline against Gradle declarations")
abstract class VerifyDaggerTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val expected: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val actual: RegularFileProperty
    @TaskAction fun check() {
        require(actual.get().asFile.readBytes().contentEquals(expected.get().asFile.readBytes())) {
            "Dagger pipeline is stale; run ./gradlew ${path.substringBeforeLast(':')}:generateDagger"
        }
    }
}
