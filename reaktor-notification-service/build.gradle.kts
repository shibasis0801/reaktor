import dev.shibasis.dependeasy.components.appleLibrary
import dev.shibasis.dependeasy.handles.Reaktor

plugins { id("dev.shibasis.dependeasy.apple-library") }

appleLibrary(Reaktor.NotificationFramework.name, extensionSafe = true)

dependencies {
    add("commonMainImplementation", projects.reaktorNotification)
    // The service extension opens sealed notices as they arrive, so it carries the keys' module.
    add("commonMainImplementation", projects.reaktorNotificationSealed)
}
