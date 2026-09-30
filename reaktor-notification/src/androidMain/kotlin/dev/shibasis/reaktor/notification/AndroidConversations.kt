package dev.shibasis.reaktor.notification

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

private const val ConversationTag = "reaktor.conversation"
private const val SelfKey = "reaktor.self"
private const val ConversationShortcutCategory = "android.shortcut.conversation"

internal fun AndroidNotificationRenderer.clearConversation(id: String) {
    context.getSystemService(NotificationManager::class.java).cancel(ConversationTag, id.notificationRequestCode())
}

internal suspend fun AndroidNotificationRenderer.showConversation(
    request: LocalNotificationRequest,
    sender: NotificationPerson,
    conversation: NotificationConversation,
): LocalNotificationId {
    val shown = LocalNotificationId(request.id)
    if (conversation.id == NotificationFocus.conversation && inForeground()) return shown
    val channelId = request.android?.channelId ?: request.categoryId
    channelRegistry.ensure(channelId)
    if (!canPost()) return shown

    val envelope = request.toEnvelope()
    val avatar = avatars.of(sender)
    val icon = IconCompat.createWithBitmap(avatar)
    val person = Person.Builder().setKey(sender.id).setName(sender.name).setIcon(icon).build()
    val code = conversation.id.notificationRequestCode()
    val manager = context.getSystemService(NotificationManager::class.java)
    val earlier = manager.activeNotifications
        .firstOrNull { it.tag == ConversationTag && it.id == code }
        ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it.notification) }
    val style = (earlier ?: NotificationCompat.MessagingStyle(Person.Builder().setKey(SelfKey).setName(config.selfName).build()))
        .addMessage(request.content.body, System.currentTimeMillis(), person)
    if (conversation.group) {
        style.setConversationTitle(conversation.title)
        style.setGroupConversation(true)
    }
    publishShortcut(conversation, person, icon, envelope)

    val smallIcon = resolveSmallIcon(request)
    val builder = NotificationCompat.Builder(context, channelId)
        .setSmallIcon(smallIcon)
        .setLargeIcon(avatar)
        .setStyle(style)
        .setShortcutId(conversation.id)
        .setLocusId(LocusIdCompat(conversation.id))
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setContentIntent(tapPendingIntent(envelope))
        .setDeleteIntent(responsePendingIntent(envelope, actionId = "dismiss", dismissed = true))
        .setAutoCancel(true)
        .setShowWhen(true)
    request.android?.colorArgb?.let { builder.setColor(it.toInt()) }
    when (request.content.sound) {
        NotificationSound.Default -> builder.setDefaults(NotificationCompat.DEFAULT_SOUND)
        NotificationSound.Silent -> builder.setSilent(true)
        is NotificationSound.Named -> Unit
    }
    (request.android?.actions ?: emptyList()).forEach { action ->
        builder.addAction(NotificationCompat.Action.Builder(smallIcon, action.title, responsePendingIntent(envelope, action.id, mutable = action.kind == NotificationActionKind.TextInput, dismissesNotification = action.dismissesNotification)).build())
    }
    manager.notify(ConversationTag, code, builder.build())
    return shown
}

private fun inForeground(): Boolean {
    val process = ActivityManager.RunningAppProcessInfo()
    ActivityManager.getMyMemoryState(process)
    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
}

private fun AndroidNotificationRenderer.publishShortcut(conversation: NotificationConversation, person: Person, icon: IconCompat, envelope: NotificationEnvelope) {
    val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(Intent.ACTION_MAIN))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    putEnvelopeExtras(intent, envelope)
    val shortcut = ShortcutInfoCompat.Builder(context, conversation.id)
        .setShortLabel(conversation.title ?: person.name ?: conversation.id)
        .setIcon(icon)
        .setLongLived(true)
        .setLocusId(LocusIdCompat(conversation.id))
        .setCategories(setOf(ConversationShortcutCategory))
        .setIntent(intent)
        .apply { if (!conversation.group) setPerson(person) }
        .build()
    runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
}
