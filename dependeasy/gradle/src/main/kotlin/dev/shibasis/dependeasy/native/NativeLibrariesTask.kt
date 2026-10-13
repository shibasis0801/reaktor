package dev.shibasis.dependeasy.native

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Copies host-specific native libraries")
abstract class NativeLibrariesTask : Sync() {
    @get:OutputDirectory abstract val generatedDirectory: DirectoryProperty
    init { into(generatedDirectory) }
}
