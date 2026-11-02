package com.sayang.translate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Watches chat notifications and posts a translated copy of each new incoming message. */
class ChatListener : NotificationListenerService() {

    private val scope = MainScope()
    private val seen = LinkedHashSet<String>()
    private var connectedAt = 0L

    private data class Message(val sender: String, val text: String, val time: Long)

    override fun onListenerConnected() {
        connectedAt = System.currentTimeMillis()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_translations), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!ChatApps.isWatched(sbn.packageName, prefs.notificationApps)) return
        val notification = sbn.notification
        if (notification.flags and SKIPPED_FLAGS != 0) return
        val first = prefs.myLanguage ?: return
        val second = prefs.secondLanguage
        val skip = prefs.notificationSkips()

        // Chat apps repost the whole conversation with each new message.
        messages(notification, sbn.postTime)
            .filter { it.time >= connectedAt && remember(it) }
            .forEach { message -> scope.launch { translateAndPost(message, sbn, first, second, skip) } }
    }

    private fun remember(message: Message): Boolean {
        val isNew = seen.add("${message.sender}|${message.time}|${message.text}")
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        return isNew
    }

    private fun messages(notification: Notification, postTime: Long): List<Message> {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val stack = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>().orEmpty()
        if (stack.isEmpty()) {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            return if (text.isBlank()) emptyList() else listOf(Message(title, text, postTime))
        }
        return stack.mapNotNull { bundle ->
            // Messages with no sender are the phone owner's own replies.
            val sender = senderOf(bundle) ?: return@mapNotNull null
            val text = bundle.getCharSequence("text")?.toString().orEmpty()
            if (text.isBlank()) null else Message(sender, text, bundle.getLong("time", postTime))
        }
    }

    @Suppress("DEPRECATION")
    private fun senderOf(bundle: Bundle): String? =
        (bundle.getCharSequence("sender") ?: bundle.getParcelable<android.app.Person>("sender_person")?.name)
            ?.toString()

    private suspend fun translateAndPost(
        message: Message,
        sbn: StatusBarNotification,
        first: String,
        second: String,
        skip: Set<String>,
    ) {
        // Messages already in either chosen language stay as they are, and never leave the phone.
        if (!OfflineTranslator.mightNeedTranslation(message.text, skip)) return
        val outcomes = coroutineScope {
            listOf(first, second).distinct()
                .map { async { ChatTranslator.translate(this@ChatListener, listOf(message.text), skip, it) } }
                .awaitAll()
        }
        val native = outcomes.first()
        val translated = native?.items?.firstOrNull()?.translated ?: return
        val body = listOfNotNull(translated, outcomes.getOrNull(1)?.items?.firstOrNull()?.translated)
            .distinct()
            .joinToString("\n\n")
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(message.sender)
            .setContentText(translated)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(sbn.notification.contentIntent)
            .setAutoCancel(true)
        if (native.offline) builder.setSubText(getString(R.string.offline_translation))
        getSystemService(NotificationManager::class.java).notify(message.hashCode(), builder.build())
        removeOriginal(sbn)
    }

    /** The translation replaces the chat app's copy, so the untranslated text doesn't stay in the panel. */
    private fun removeOriginal(sbn: StatusBarNotification) {
        cancelNotification(sbn.key)
        val group = activeNotifications.filter { it.packageName == sbn.packageName && it.groupKey == sbn.groupKey }
        val (summaries, children) = group.partition { it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
        if (children.none { it.key != sbn.key }) summaries.forEach { cancelNotification(it.key) }
    }

    companion object {
        private const val CHANNEL = "translations"
        private const val MAX_SEEN = 200
        private const val SKIPPED_FLAGS = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT
    }
}

object ChatApps {
    // A chosen list keeps bank texts and login codes away from Google.
    private const val DEBUG_ONLY = "com.android.shell"

    fun isWatched(packageName: String, chosen: Set<String>) =
        packageName in chosen || (BuildConfig.DEBUG && packageName == DEBUG_ONLY)
}
