package dev.shibasis.reaktor.tooling.device

import dev.shibasis.reaktor.tooling.DevelopmentDevice
import dev.shibasis.reaktor.tooling.DeviceTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

object BrowserTabs {
    private val listed = MutableStateFlow<List<DevelopmentDevice>>(emptyList())
    private val refusal = MutableStateFlow<String?>(NoDoor)

    val devices: StateFlow<List<DevelopmentDevice>> = listed
    val unavailable: StateFlow<String?> = refusal

    fun open() {
        refusal.value = null
    }

    fun refuse(reason: String) {
        refusal.value = reason
        listed.value = emptyList()
    }

    fun publish(tabs: List<DevelopmentDevice>) {
        listed.value = tabs
    }

    fun tab(id: String, name: String, connected: Boolean) =
        DevelopmentDevice(id, name, DeviceTransport.Web, if (connected) Connected else Reconnecting, "Web")

    const val Connected = "Connected"
    const val Reconnecting = "Reconnecting"
    private const val NoDoor = "Browser tabs connect to an open DevTools pane in Hangar"
}

class BrowserDeviceBackend : DeviceBackend {
    override val transport: DeviceTransport = DeviceTransport.Web
    override val name: String = "Browser tabs · DevTools door"

    override fun unavailableReason(): String? = BrowserTabs.unavailable.value

    override fun devices(): Flow<List<DevelopmentDevice>> = BrowserTabs.devices

    override suspend fun list(): List<DevelopmentDevice> = BrowserTabs.devices.value

    override suspend fun session(device: DevelopmentDevice): DeviceSession = BrowserDeviceSession(device)

    override fun close() = Unit
}

class BrowserDeviceSession(override val device: DevelopmentDevice) : DeviceSession {
    override val capabilities: List<DeviceCapabilityReport> = DeviceOperation.entries.map { operation ->
        DeviceCapabilityReport(operation, operation == DeviceOperation.Discover, if (operation == DeviceOperation.Discover) null else refusal(operation), "DevTools door")
    }

    private fun refused(operation: DeviceOperation): Nothing = throw UnsupportedOperationException(refusal(operation))

    private fun refusal(operation: DeviceOperation): String =
        "${device.name} is a browser tab: ${operation.name} goes through the page's DevTools agent, not the device layer"

    override suspend fun shell(command: String): String = refused(DeviceOperation.Shell)
    override fun shellLines(command: String): Flow<String> = emptyFlow()
    override fun logs(): Flow<String> = emptyFlow()
    override suspend fun listApps(): List<String> = emptyList()
    override suspend fun listProcesses(): List<DeviceProcess> = emptyList()
    override suspend fun install(artifactPath: String) = refused(DeviceOperation.Install)
    override suspend fun uninstall(applicationId: String) = refused(DeviceOperation.Uninstall)
    override suspend fun launch(applicationId: String, arguments: List<String>) = refused(DeviceOperation.Launch)
    override suspend fun terminate(applicationId: String) = refused(DeviceOperation.Terminate)
    override suspend fun pull(remotePath: String): ByteArray = refused(DeviceOperation.PullFile)
    override suspend fun push(bytes: ByteArray, remotePath: String) = refused(DeviceOperation.PushFile)
    override suspend fun screenshot(): ByteArray = refused(DeviceOperation.Screenshot)
    override suspend fun input(event: DeviceInput) = refused(DeviceOperation.Input)
    override suspend fun openUrl(url: String) = refused(DeviceOperation.DeepLink)
    override suspend fun forward(localPort: Int, remote: RemoteSocket): Int = refused(DeviceOperation.Forward)
    override suspend fun removeForward(localPort: Int) = Unit
    override fun close() = Unit
}
