plugins { id("dev.shibasis.dependeasy.pipeline") }

dependeasy {
    appleLibrary(dev.shibasis.dependeasy.handles.Reaktor.NotificationFramework.name, extensionSafe = true)
}
