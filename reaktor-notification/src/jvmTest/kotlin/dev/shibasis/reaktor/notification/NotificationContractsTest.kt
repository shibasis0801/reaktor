package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.core.adapters.Permission
import dev.shibasis.reaktor.core.adapters.PermissionAdapter
import dev.shibasis.reaktor.core.adapters.PermissionResult
import dev.shibasis.reaktor.core.adapters.NotificationPermissionState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class NotificationContractsTest {
    @Test
    fun immediateTriggerNeverRepeats() {
        assertFalse(NotificationTrigger.Immediate.isRepeating)
    }

    @Test
    fun timeIntervalRepeatsOnlyWithAPositiveDelay() {
        assertTrue(NotificationTrigger.TimeInterval(1.hours, repeats = true).isRepeating)
        assertFalse(NotificationTrigger.TimeInterval(1.hours, repeats = false).isRepeating)
        // A zero delay would re-fire in a tight loop, so it is refused whatever the flag says.
        assertFalse(NotificationTrigger.TimeInterval(Duration.ZERO, repeats = true).isRepeating)
    }

    @Test
    fun recurringCalendarTriggerRepeats() {
        assertTrue(NotificationTrigger.Calendar(hour = 18, minute = 30, repeats = true).isRepeating)
        assertFalse(NotificationTrigger.Calendar(hour = 18, minute = 30, repeats = false).isRepeating)
    }

    @Test
    fun calendarTriggerPinnedToADateNeverRepeats() {
        // An absolute date has exactly one valid firing time; repeating it would fire immediately.
        assertFalse(
            NotificationTrigger.Calendar(
                year = 2026,
                month = 8,
                day = 4,
                hour = 18,
                minute = 30,
                repeats = true,
            ).isRepeating,
        )
        assertFalse(NotificationTrigger.Calendar(day = 4, hour = 18, repeats = true).isRepeating)
    }

    @Test
    fun actionsDismissTheirNotificationUnlessTheyOptOut() {
        // Android cannot rely on auto-cancel for buttons, so the common contract has to carry the
        // intent: a plain button clears the notification, a snooze or reply can keep it.
        assertTrue(NotificationActionSpec(id = "not_today", title = "Not today").dismissesNotification)
        assertFalse(
            NotificationActionSpec(
                id = "snooze",
                title = "Snooze",
                dismissesNotification = false,
            ).dismissesNotification,
        )
    }

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
    fun dispatchPayloadBuildsEnvelopeBackedProviderData() {
        val payload = NotificationDispatchPayload(
            deliveryId = "delivery-1",
            notificationId = "notification-1",
            endpointId = "endpoint-1",
            userId = "user-1",
            platform = "android",
            provider = "fcm",
            token = "token-1",
            title = "Title",
            body = "Body",
            categoryId = "system",
            dryRun = true,
        )

        val data = payload.providerData()
        val fcmBody = payload.fcmRequestBody()
        val result = payload.dispatchResult(
            status = NotificationDeliveryStatuses.DryRunAccepted,
            dispatched = false,
            dryRun = true,
        )

        assertEquals("notification-1", data["reaktor_notification_id"])
        assertEquals("reaktor.notification.delivery", data["reaktor_notification_type"])
        assertEquals("delivery-1", data["reaktor_correlation_id"])
        assertEquals("delivery-1", data["data_delivery_id"])
        assertEquals("none", data["reaktor_route_type"])
        assertTrue(fcmBody.contains("\"token\":\"token-1\""))
        assertTrue(fcmBody.contains("\"data\""))
        assertTrue(fcmBody.contains("\"android\""))
        assertTrue(fcmBody.contains("\"priority\":\"HIGH\""))
        assertTrue(fcmBody.contains("\"channel_id\":\"system\""))
        assertTrue(fcmBody.contains("\"apns\""))
        assertTrue(fcmBody.contains("\"apns-priority\":\"10\""))
        assertTrue(fcmBody.contains("\"thread-id\":\"system\""))
        assertEquals("delivery-1", result.deliveryId)
        assertEquals(NotificationDeliveryStatuses.DryRunAccepted, result.status)
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
    fun notificationAdapterUsesCorePermissionAdapter() {
        runBlocking {
            val client = InMemoryNotificationsClient(NotificationPlatform.Android)
            val permissionAdapter = client.permissionAdapter

            assertFalse((client as Any) is PermissionAdapter<*>)
            assertTrue(permissionAdapter.request(Permission.NOTIFICATIONS))
            assertEquals(
                PermissionResult.Granted,
                permissionAdapter.requestOptional(Permission.NOTIFICATIONS)[Permission.NOTIFICATIONS],
            )
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
