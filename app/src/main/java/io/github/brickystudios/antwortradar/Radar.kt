package io.github.brickystudios.antwortradar

/** UNREAD: notification still there. READ: notification gone, no answer seen. DONE: answered or swiped away in the app. */
enum class State { UNREAD, READ, DONE }

data class Entry(
    val key: String,
    val name: String,
    val preview: String,
    val group: Boolean,
    /** first incoming message that is still unanswered */
    val since: Long,
    /** newest incoming message; a repost with an older or equal time is not new */
    val last: Long,
    val state: State,
    val readAt: Long = 0,
    val doneAt: Long = 0,
)

/** One chat notification, reduced to what the radar keeps. */
data class Incoming(
    val key: String,
    val name: String,
    val text: String,
    val group: Boolean,
    val fromMe: Boolean,
    val time: Long,
)

/** The whole state machine. Pure functions, so it runs in unit tests without a phone. */
object Radar {
    const val PREVIEW = 80
    val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

    // NotificationListenerService.REASON_*: the person saw the chat (tapped, swiped, opened it in WhatsApp or on WhatsApp Web)
    val SEEN = mapOf(
        1 to "angetippt", 2 to "weggewischt", 3 to "alle gelöscht",
        8 to "von WhatsApp entfernt", 9 to "von WhatsApp alle entfernt", 12 to "mit Gruppe entfernt",
    )

    /** WhatsApp appends counters to titles: "Lisa (2 Nachrichten)", "Projekt (3 messages)". */
    fun cleanName(title: String) = title.replace(Regex("""\s*\(\d+\s*\p{L}+\)\s*$"""), "").trim()

    fun mentioned(text: String, names: List<String>) =
        names.any { it.isNotBlank() && text.contains("@" + it.trim(), ignoreCase = true) }

    /** A notification was posted or updated. null = do not track this chat. */
    fun posted(old: Entry?, m: Incoming, names: List<String>): Entry? {
        if (m.fromMe) return old?.let { if (it.state == State.DONE) it else it.copy(state = State.DONE, doneAt = m.time) }
        if (old != null && m.time <= old.last) return old // repost of a message we already have
        if (m.group && !mentioned(m.text, names)) return old
        val preview = m.text.take(PREVIEW)
        return if (old == null || old.state == State.DONE)
            Entry(m.key, m.name, preview, m.group, since = m.time, last = m.time, state = State.UNREAD)
        else old.copy(name = m.name, preview = preview, last = m.time, state = State.UNREAD)
    }

    /** The notification disappeared. Only reasons in [SEEN] count as "read". */
    fun removed(old: Entry?, reason: Int, now: Long): Entry? =
        if (old != null && old.state == State.UNREAD && reason in SEEN) old.copy(state = State.READ, readAt = now) else old

    fun done(old: Entry, now: Long) = old.copy(state = State.DONE, doneAt = now)

    fun waiting(all: Collection<Entry>) = all.filter { it.state == State.READ }.sortedBy { it.since }
    fun unread(all: Collection<Entry>) = all.filter { it.state == State.UNREAD }.sortedByDescending { it.last }
    fun doneSince(all: Collection<Entry>, from: Long) = all.count { it.state == State.DONE && it.doneAt >= from }

    /** Keep the file small: answered chats older than 30 days go. */
    fun prune(all: Collection<Entry>, now: Long) =
        all.filterNot { it.state == State.DONE && now - it.doneAt > 30L * DAY }

    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    /** "40 Min", "5 Std", "3 T" */
    fun age(ms: Long): String = when {
        ms < HOUR -> "${maxOf(1, ms / 60_000)} Min"
        ms < DAY -> "${ms / HOUR} Std"
        else -> "${ms / DAY} T"
    }
}
