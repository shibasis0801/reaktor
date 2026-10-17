plugins { id("dev.shibasis.dependeasy.pipeline") }

dependeasy {
    appleLibrary("Reaktor", exports = listOf(projects.reaktorCore, projects.reaktorIo, projects.reaktorFlexbuffer))
}
