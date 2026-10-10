plugins { id("dev.shibasis.dependeasy.pipeline") }

dependeasy {
    appleLibrary("Reaktor", exports = listOf(":reaktor-core", ":reaktor-io", ":reaktor-flexbuffer").map { project(it) })
}
