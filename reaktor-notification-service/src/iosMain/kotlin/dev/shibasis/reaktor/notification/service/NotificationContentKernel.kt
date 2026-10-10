@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.shibasis.reaktor.notification.service

import platform.Intents.*
import platform.UserNotifications.*

internal object NotificationContentKernel {
    fun enrich(content: UNNotificationContent, complete: (UNNotificationContent) -> Unit) {
        val info = content.userInfo
        val senderId = info["reaktor_sender_id"] as? String
        val conversationId = info["reaktor_conversation_id"] as? String
        if (senderId == null || conversationId == null) { complete(content); return }
        NotificationPhoto.load(info["reaktor_sender_photo"] as? String) { photo ->
            val image = photo?.let { INImage.imageWithImageData(it.data) }
            val sender = INPerson(INPersonHandle(senderId, INPersonHandleTypeUnknown), null,
                info["reaktor_sender_name"] as? String ?: content.title, image, null, senderId)
            val me = INPerson(INPersonHandle("me", INPersonHandleTypeUnknown), null, null, null, null, null, true)
            val group = info["reaktor_conversation_group"] == "true"
            val title = info["reaktor_conversation_title"] as? String
            val intent = INSendMessageIntent(if (group) listOf(me, sender) else null,
                INOutgoingMessageTypeOutgoingMessageText, content.body,
                if (group) title?.let(::INSpeakableString) else null, conversationId, null, sender, null)
            if (group && image != null) {
                intent.setImage(image, forParameterNamed = "speakableGroupName")
            }
            val interaction = INInteraction(intent, null)
            interaction.direction = INInteractionDirectionIncoming
            interaction.donateInteractionWithCompletion {
                val styled = content.contentByUpdatingWithProvider(intent as UNNotificationContentProvidingProtocol, null)
                complete(styled ?: NotificationPhoto.attach(photo, content))
            }
        }
    }
}
