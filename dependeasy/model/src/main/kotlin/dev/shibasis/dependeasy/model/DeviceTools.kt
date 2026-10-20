package dev.shibasis.dependeasy.model

object DeviceTools {
    fun xcodebuild() = listOf("/usr/bin/xcrun", "--find", "xcodebuild")
    fun androidInstall(adb: String, serial: String, app: String, replace: Boolean) =
        listOf(adb, "-s", serial, "install") + (if (replace) listOf("-r") else emptyList()) + app
    fun androidUninstall(adb: String, serial: String, applicationId: String) =
        listOf(adb, "-s", serial, "uninstall", applicationId)
    fun appleIdentifier(app: String) = listOf("/usr/bin/plutil", "-extract", "CFBundleIdentifier", "raw", "-o", "-", "$app/Info.plist")
    fun appleInstall(serial: String, app: String) = listOf("/usr/bin/xcrun", "devicectl", "device", "install", "app", "--device", serial, app)
}
