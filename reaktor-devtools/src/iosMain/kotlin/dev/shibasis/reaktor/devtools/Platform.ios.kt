package dev.shibasis.reaktor.devtools

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSDate
import platform.Foundation.NSLog
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIDevice

/**
 * Apple.
 *
 * A simulator shares the host's loopback, so the workbench reaches the agent with no forwarding at
 * all. A physical device needs usbmux forwarding through `idb_companion --forward`; `devicectl`
 * handles discovery and app lifecycle. There are no Android-style abstract sockets on Apple.
 */
@OptIn(ExperimentalNativeApi::class)
private object DarwinPlatformInfo : DevToolsPlatformInfo {
    override val name: String = "Darwin"
    override val osVersion: String =
        UIDevice.currentDevice.systemName + " " + UIDevice.currentDevice.systemVersion
    override val deviceModel: String = UIDevice.currentDevice.model
    override val frameVitalsFidelity: Fidelity = Fidelity.Stream
    override val memoryVitalsFidelity: Fidelity = Fidelity.Stream

    override fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

    override fun mirrorLog(level: LogLevel, subsystem: String, message: String) {
        NSLog("[%s] %s: %s", level.name, subsystem, message)
    }
}

actual fun devToolsPlatformInfo(): DevToolsPlatformInfo = DarwinPlatformInfo

actual fun devToolsTransport(port: Int): AgentTransport = RelistenOnForeground(TcpAgentTransport(port))

private class RelistenOnForeground(private val tcp: TcpAgentTransport) : AgentTransport by tcp {
    private val observer = NSNotificationCenter.defaultCenter.addObserverForName(
        name = UIApplicationWillEnterForegroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> tcp.relisten() }

    override fun close() {
        NSNotificationCenter.defaultCenter.removeObserver(observer)
        tcp.close()
    }
}
