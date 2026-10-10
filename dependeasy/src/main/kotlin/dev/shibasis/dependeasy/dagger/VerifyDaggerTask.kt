package dev.shibasis.dependeasy.dagger

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Checks the checked-in adapter against Gradle declarations")
abstract class VerifyDaggerTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val expected: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val actual: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val expectedConfiguration: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val actualConfiguration: RegularFileProperty

    @TaskAction fun check() {
        val stale = listOf("index.ts", "runtime.ts").filter { name ->
            val file = actual.file(name).get().asFile
            !file.isFile || !file.readBytes().contentEquals(expected.file(name).get().asFile.readBytes())
        } + if (actualConfiguration.get().asFile.readBytes().contentEquals(expectedConfiguration.get().asFile.readBytes())) emptyList() else listOf("dagger.json")
        require(stale.isEmpty()) { "Dagger adapter is stale (${stale.joinToString()}); run ./gradlew generateDagger" }
    }
}
