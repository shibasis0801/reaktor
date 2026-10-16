package dev.shibasis.reaktor.tooling

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
enum class DeviceTransport { Adb, Simctl, Idb }

@Serializable
data class DevelopmentDevice(
    val id: String,
    val name: String,
    val transport: DeviceTransport,
    val state: String,
    val runtime: String = "",
) {
    val key: String get() = "${transport.name.lowercase()}:$id"
    val ready: Boolean get() = when (transport) {
        DeviceTransport.Adb -> state == "device"
        DeviceTransport.Simctl -> state.equals("Booted", true)
        DeviceTransport.Idb -> state.equals("Booted", true) || state.equals("Connected", true)
    }
}

object DeviceTools {
    fun inventory(devices: List<DevelopmentDevice>): String =
        Json.encodeToString(ListSerializer(DevelopmentDevice.serializer()), order(devices))

    fun parseInventory(transport: DeviceTransport, output: String): List<DevelopmentDevice> =
        order(Json.decodeFromString(ListSerializer(DevelopmentDevice.serializer()), output.trim()).also { devices ->
            require(devices.all { it.transport == transport }) { "The ${transport.name} inventory lists another transport" }
        })

    private fun order(devices: List<DevelopmentDevice>): List<DevelopmentDevice> =
        devices.distinctBy(DevelopmentDevice::key).sortedWith(compareBy<DevelopmentDevice> { !it.ready }.thenBy { it.name })
}
