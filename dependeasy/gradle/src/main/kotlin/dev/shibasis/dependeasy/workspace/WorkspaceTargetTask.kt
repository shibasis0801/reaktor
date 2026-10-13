package dev.shibasis.dependeasy.workspace

import org.gradle.api.DefaultTask
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "An entrypoint whose dependencies own execution and outputs")
abstract class WorkspaceTargetTask : DefaultTask()
