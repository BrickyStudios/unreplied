package io.github.brickystudios.antwortradar

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Reads WhatsApp notifications and nothing else. A chat whose notification disappears because it was opened,
 * tapped or swiped becomes "read, not answered" (Radar.SEEN). Every removal lands in the check log, so the
 * user can see which reason code their WhatsApp actually sends.
 */
class RadarListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Store.init(this)
        Summary.schedule(this) // the system binds this service after every boot, so no boot receiver is needed
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val m = read(sbn) ?: return
        Store.init(this)
        val old = Store.entries.value[m.key]
        val new = Radar.posted(old, m, Prefs(this).nameList)
        val event = when {
            new?.state == old?.state -> null
            new?.state == State.DONE -> "deine Antwort gesehen → erledigt"
            else -> "neue Nachricht"
        }
        Store.put(m.key, new, event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!tracked(sbn)) return
        Store.init(this)
        val key = key(sbn, title(sbn.notification) ?: return)
        val old = Store.entries.value[key] ?: return // a group without @-mention, never tracked
        val new = Radar.removed(old, reason, System.currentTimeMillis())
        val became = old.state != new?.state
        Store.put(key, new, "Grund $reason (${Radar.SEEN[reason] ?: "System"})" + if (became) " → gelesen" else "")
    }

    private fun tracked(sbn: StatusBarNotification) =
        (sbn.packageName in Radar.PACKAGES || (BuildConfig.DEBUG && sbn.packageName == "com.android.shell")) &&
            sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0

    private fun title(n: Notification): String? {
        val x = n.extras
        val t = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE)
        return t?.toString()?.let(Radar::cleanName)?.takeIf { it.isNotEmpty() }
    }

    // WhatsApp gives every chat its own shortcut id (Android 11+); the title is the fallback
    private fun key(sbn: StatusBarNotification, title: String) = sbn.notification.shortcutId ?: sbn.tag ?: title

    private fun read(sbn: StatusBarNotification): Incoming? {
        if (!tracked(sbn)) return null
        val n = sbn.notification
        val title = title(n) ?: return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val text = (last?.text ?: n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        // in MessagingStyle your own messages have no sender, or the device user as sender
        val fromMe = last != null && (last.person == null || last.person?.name == style.user.name)
        val group = style?.isGroupConversation ?: n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        return Incoming(key(sbn, title), title, text, group, fromMe, last?.timestamp ?: sbn.postTime)
    }
}
