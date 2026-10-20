import dev.shibasis.dependeasy.components.appleLibrary
import dev.shibasis.dependeasy.handles.Reaktor

plugins { id("dev.shibasis.dependeasy.apple-library") }

appleLibrary(Reaktor.NotificationFramework.name, extensionSafe = true)
