import dev.shibasis.dependeasy.components.appleLibrary
import dev.shibasis.dependeasy.handles.Reaktor

plugins { id("dev.shibasis.dependeasy.apple-library") }

appleLibrary("Reaktor", exports = listOf(Reaktor.Core, Reaktor.Io, Reaktor.Flexbuffer))
