package dev.shibasis.reaktor.notification

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NotificationContractsTest {
    @Test
    fun envelopeRoundTripsThroughProviderDataMap() {
        val envelope = NotificationEnvelope(
            id = "n-1",
            type = "chat.mention",
            categoryId = "messages",
            content = NotificationContent(
                title = "Mention",
                body = "Open notification",
                subtitle = "Chat",
            ),
            route = NotificationRoute.GraphAction("reaktor.notification.open", """{"notificationId":"c1"}"""),
            correlationId = "corr-1",
            data = mapOf("chatId" to "c1"),
        )

        val decoded = NotificationEnvelope.fromDataMap(envelope.toDataMap())

        assertEquals(envelope.id, decoded.id)
        assertEquals(envelope.type, decoded.type)
        assertEquals(envelope.categoryId, decoded.categoryId)
        assertEquals(envelope.content.title, decoded.content.title)
        assertEquals(envelope.content.body, decoded.content.body)
        assertEquals(envelope.route, decoded.route)
        assertEquals("c1", decoded.data["chatId"])
    }

    @Test
    fun inMemoryClientSupportsFirstSliceOperations() {
        runBlocking {
            val client = InMemoryNotificationsClient(NotificationPlatform.Android)
            client.registerCategories(DefaultNotificationCategories)

            val permission = client.requestPermissions()
            val token = client.getDeviceToken()
            val endpoint = client.registerRemoteEndpoint("user-1")
            val localId = client.scheduleLocal(
                LocalNotificationRequest(
                    id = "local-1",
                    categoryId = "messages",
                    content = NotificationContent("Title", "Body"),
                    route = NotificationRoute.OpenPath("/chats/dev"),
                ),
            )

            assertEquals(NotificationPermissionState.Granted, permission.state)
            assertNotNull(token)
            assertTrue(endpoint.registered)
            assertEquals("local-1", localId.value)
            assertEquals("local-1", client.lastLocal?.id)
        }
    }

    @Test
    fun devHarnessTracksNotificationFlow() {
        runBlocking {
            val client = InMemoryNotificationsClient(NotificationPlatform.Ios)
            val harness = BaseNotificationDevHarness(client, NotificationPlatform.Ios)

            harness.registerDefaultCategories()
            harness.requestPermission()
            harness.refreshToken()
            harness.sendLocal()
            harness.injectRemoteEnvelope()
            val finalState = harness.simulateTap()

            assertEquals(NotificationPermissionState.Granted, finalState.permission.state)
            assertEquals(5, finalState.categories.size)
            assertNotNull(finalState.token)
            assertNotNull(finalState.lastLocalNotificationId)
            assertNotNull(finalState.lastReceived)
            assertNotNull(finalState.lastResponse)
        }
    }
}
