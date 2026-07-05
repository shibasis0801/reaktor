package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.NotificationPermissionClient
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.registerProvider

object NotificationPorts {
    const val Notifications = "notifications"
    const val Permissions = "notificationPermissions"
}

open class NotificationsNode(
    graph: Graph,
    val client: NotificationsClient,
) : BasicNode(graph) {
    val notifications = registerProvider<NotificationsClient>(NotificationPorts.Notifications, client)
    val permissions = registerProvider<NotificationPermissionClient>(NotificationPorts.Permissions, client)

    override fun toString(): String =
        "${super.toString()} [Notifications] client='${client::class.simpleName}'"
}

fun Graph.NotificationsNode(client: NotificationsClient): NotificationsNode {
    val node = NotificationsNode(this, client)
    attach(node)
    return node
}
