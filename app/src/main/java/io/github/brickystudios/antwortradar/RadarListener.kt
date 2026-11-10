package io.github.brickystudios.antwortradar

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Reads notifications of the messengers switched on in the settings and nothing else. A chat whose notification
 * disappears because it was opened, tapped or swiped becomes "read, not answered" (Radar.SEEN). Every removal lands
 * in the check log, so the user can see which reason code their messenger actually sends.
 */
class RadarListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Store.init(this)
        Live.connected = true
        Store.note(getString(R.string.app_name), Ev.CONNECTED)
        Summary.schedule(this) // the system binds this service after every boot, so no boot receiver is needed
        // chats that arrived while the radar was off still count as unread
        runCatching { activeNotifications }.getOrNull()?.forEach { onNotificationPosted(it) }
    }

    // Android (and battery savers on some phones) can unbind the listener, e.g. after an app update. Ask to be bound
    // again at once; until then nothing arrives, and the replay in onListenerConnected adds the missed chats late.
    override fun onListenerDisconnected() {
        Live.connected = false
        Store.init(this)
        Store.note(getString(R.string.app_name), Ev.DISCONNECTED)
        requestRebind(ComponentName(this, RadarListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!tracked(sbn)) return
        val m = read(sbn) ?: return
        Store.init(this)
        val prefs = Prefs(this)
        Live.remember(m.key, sbn.notification)
        val old = Store.entries.value[m.key]
        val new = Radar.posted(old, m, prefs.nameList, prefs.ignored.keys)
        if (new != null) Store.touch(m.key, m.name, m.time) // ignored chats and unmentioned groups leave no trace
        val ev = when {
            new?.state == old?.state -> null
            new?.state == State.DONE -> Ev.ANSWERED
            else -> Ev.NEW
        }
        // seconds between the messenger posting and us hearing of it; large values point at an unbound listener
        Store.put(m.key, new, ev, ((System.currentTimeMillis() - sbn.postTime) / 1000).toInt().coerceAtLeast(0))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        if (!tracked(sbn)) return
        Store.init(this)
        val key = key(sbn, title(sbn.notification) ?: return)
        val old = Store.entries.value[key] ?: return // a group without @-mention or an ignored chat, never tracked
        val new = Radar.removed(old, reason, System.currentTimeMillis())
        Store.put(key, new, if (old.state != new?.state) Ev.READ else Ev.REMOVED, reason)
    }

    private fun tracked(sbn: StatusBarNotification): Boolean {
        val pkg = sbn.packageName
        val n = sbn.notification
        val app = pkg in Prefs(this).enabled || (BuildConfig.DEBUG && pkg == "com.android.shell")
        if (!app || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val ongoing = n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        return Radar.isChat(n.category, ongoing, NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) != null)
    }

    private fun title(n: Notification): String? {
        val x = n.extras
        val t = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE)
        return t?.toString()?.let(Radar::cleanName)?.takeIf { it.isNotEmpty() }
    }

    // Messengers give every chat its own shortcut id (Android 11+); the tag and then the title are the fallbacks
    private fun key(sbn: StatusBarNotification, title: String) =
        Radar.key(sbn.packageName, sbn.notification.shortcutId ?: sbn.tag ?: title)

    private fun read(sbn: StatusBarNotification): Incoming? {
        val n = sbn.notification
        val title = title(n) ?: return null
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val text = (last?.text ?: n.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        // in MessagingStyle your own messages have no sender, or the device user as sender
        val fromMe = last != null && (last.person == null || last.person?.name == style.user.name)
        val group = style?.isGroupConversation ?: n.extras.getBoolean("android.isGroupConversation") // EXTRA_IS_GROUP_CONVERSATION, the constant is API 28+
        return Incoming(key(sbn, title), title, text, group, fromMe, last?.timestamp ?: sbn.postTime)
    }
}
