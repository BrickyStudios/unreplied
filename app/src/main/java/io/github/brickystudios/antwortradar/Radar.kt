package io.github.brickystudios.antwortradar

import java.util.Calendar

/** UNREAD: notification still there. READ: notification gone, no answer seen. DONE: answered or swiped away in the app. */
enum class State { UNREAD, READ, DONE }

data class Entry(
    /** "<package>|<chat id>", so the same name in two messengers stays two chats */
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
    /** hidden from the waiting list until then ("Später") */
    val snoozeUntil: Long = 0,
    /** when a favorite was last nudged; a nudge counts for one wait only (nudgedAt >= since) */
    val nudgedAt: Long = 0,
) {
    val app get() = key.substringBefore('|')
}

/** One chat notification, reduced to what the radar keeps. */
data class Incoming(
    val key: String,
    val name: String,
    val text: String,
    val group: Boolean,
    val fromMe: Boolean,
    val time: Long,
    /** newest message of yours in the notification's history, if the messenger shows it */
    val repliedAt: Long? = null,
    /** their first message after your last one (or the oldest in the history when none is yours) */
    val waitingSince: Long? = null,
)

/** Last contact with a person in either direction; the base for "Lange nicht geschrieben". */
data class Contact(val key: String, val name: String, val last: Long, val favorite: Boolean = false)

enum class Span { MIN, HOUR, DAY, WEEK }

/** The whole state machine. Pure functions, so it runs in unit tests without a phone. */
object Radar {
    const val PREVIEW = 80
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR
    const val WEEK = 7 * DAY

    // NotificationListenerService.REASON_*: the person saw the chat (tapped, swiped, opened it in the app or on desktop)
    val SEEN = setOf(1, 2, 3, 8, 9, 12)

    fun key(app: String, chat: String) = "$app|$chat"

    /**
     * Chat messages only. Messengers also post ongoing service notices ("Checking for new messages" titled
     * "WhatsApp"), missed calls and progress bars; those would show up as fake chats.
     */
    fun isChat(category: String?, ongoing: Boolean, messagingStyle: Boolean) =
        !ongoing && (if (category != null) category == "msg" /* Notification.CATEGORY_MESSAGE */ else messagingStyle)

    /** Messengers append counters to titles: "Lisa (2 Nachrichten)", "Projekt (3 messages)". */
    fun cleanName(title: String) = title.replace(Regex("""\s*\(\d+\s*\p{L}+\)\s*$"""), "").trim()

    fun mentioned(text: String, names: List<String>) =
        names.any { it.isNotBlank() && text.contains("@" + it.trim(), ignoreCase = true) }

    /** A notification was posted or updated. null = do not track this chat. */
    fun posted(old: Entry?, m: Incoming, names: List<String>, ignored: Set<String> = emptySet()): Entry? {
        if (m.key in ignored) return null
        if (m.fromMe) return old?.let { if (it.state == State.DONE) it else done(it, m.time) }
        if (old != null && m.time <= old.last) return old // repost of a message we already have
        if (m.group && !mentioned(m.text, names)) return old
        val preview = m.text.take(PREVIEW)
        if (old == null || old.state == State.DONE)
            return Entry(m.key, m.name, preview, m.group, since = minOf(m.waitingSince ?: m.time, m.time), last = m.time, state = State.UNREAD)
        // you answered inside the messenger after this wait began: the wait restarts at their first message after that
        val restart = m.repliedAt != null && m.repliedAt >= old.since && m.waitingSince != null
        return old.copy(
            name = m.name, preview = preview, last = m.time, state = State.UNREAD, snoozeUntil = 0,
            since = if (restart) minOf(m.waitingSince!!, m.time) else old.since,
        )
    }

    /** The notification disappeared. Only reasons in [SEEN] count as "read". */
    fun removed(old: Entry?, reason: Int, now: Long): Entry? =
        if (old != null && old.state == State.UNREAD && reason in SEEN) old.copy(state = State.READ, readAt = now) else old

    fun done(old: Entry, now: Long) = old.copy(state = State.DONE, doneAt = now, snoozeUntil = 0)
    fun snooze(old: Entry, until: Long) = old.copy(snoozeUntil = until)

    fun waiting(all: Collection<Entry>, now: Long) =
        all.filter { it.state == State.READ && it.snoozeUntil <= now }.sortedBy { it.since }
    fun snoozed(all: Collection<Entry>, now: Long) =
        all.filter { it.state == State.READ && it.snoozeUntil > now }.sortedBy { it.snoozeUntil }
    fun unread(all: Collection<Entry>) = all.filter { it.state == State.UNREAD }.sortedByDescending { it.last }
    fun doneSince(all: Collection<Entry>, from: Long) = all.count { it.state == State.DONE && it.doneAt >= from }

    /** "Später" ran out: back on the list, and worth one notice. */
    fun dueSnoozes(all: Collection<Entry>, now: Long) = all.filter { it.state == State.READ && it.snoozeUntil in 1..now }

    /** Favorites who have waited [hours] or longer and were not nudged in this wait yet. */
    fun dueFavorites(all: Collection<Entry>, favorites: Set<String>, now: Long, hours: Int) = all.filter {
        it.state == State.READ && it.key in favorites && it.snoozeUntil <= now && now - it.since >= hours * HOUR && it.nudgedAt < it.since
    }

    /** When the reminder alarm should fire next: the earliest snooze end or favorite nudge, null when nothing is pending. */
    fun nextReminder(all: Collection<Entry>, favorites: Set<String>, now: Long, hours: Int): Long? {
        // a snooze that ran out while the phone was off is due at once
        val snoozes = all.filter { it.state == State.READ && it.snoozeUntil > 0 }.map { maxOf(it.snoozeUntil, now) }
        val nudges = all.filter { it.state == State.READ && it.key in favorites && it.nudgedAt < it.since }
            .map { maxOf(it.since + hours * HOUR, it.snoozeUntil, now) }
        return (snoozes + nudges).minOrNull()
    }

    /** Answered in the last seven days and the average time from their first message to your answer. */
    fun weekStats(all: Collection<Entry>, now: Long): Pair<Int, Long> {
        val done = all.filter { it.state == State.DONE && it.doneAt >= now - WEEK && it.doneAt >= it.since }
        return done.size to if (done.isEmpty()) 0L else done.sumOf { it.doneAt - it.since } / done.size
    }

    /**
     * Read detection per messenger, from the check log: true once a removal counted as read (with its code),
     * false when the messenger only removed notifications in ways that do not count. Apps without removals are absent.
     */
    fun detection(log: List<LogLine>): Map<String, Pair<Boolean, Int>> =
        log.filter { it.app.isNotEmpty() && (it.ev == Ev.READ || it.ev == Ev.REMOVED) }.groupBy { it.app }.mapValues { (_, lines) ->
            val read = lines.firstOrNull { it.ev == Ev.READ } // the log is newest first
            if (read != null) true to read.reason else false to lines.first().reason
        }

    /** Keep the file small: answered chats older than 30 days go. */
    fun prune(all: Collection<Entry>, now: Long) =
        all.filterNot { it.state == State.DONE && now - it.doneAt > 30L * DAY }

    /** Any message in or out counts as contact; the favorite flag survives. */
    fun touch(old: Contact?, key: String, name: String, time: Long) =
        if (old != null && old.last >= time) old else Contact(key, name, time, old?.favorite ?: false)

    /** Favorites you have not written with for [weeks] weeks, longest silence first. */
    fun quiet(all: Collection<Contact>, now: Long, weeks: Int) =
        all.filter { it.favorite && now - it.last >= weeks * WEEK }.sortedBy { it.last }

    /** "Später" choices: in 3 hours, this evening at [eveningHour] (or tomorrow evening if that is past), tomorrow at 9. */
    fun laterTimes(now: Long, eveningHour: Int): List<Long> {
        fun at(daysAhead: Int, hour: Int) = Calendar.getInstance().apply {
            timeInMillis = now; add(Calendar.DAY_OF_YEAR, daysAhead)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val evening = at(0, eveningHour).let { if (it - now < HOUR) at(1, eveningHour) else it }
        return listOf(now + 3 * HOUR, evening, at(1, 9))
    }

    /** 40 min -> (40, MIN), 5 h -> (5, HOUR), 3 days -> (3, DAY), 5 weeks -> (5, WEEK) */
    fun age(ms: Long): Pair<Long, Span> = when {
        ms < HOUR -> maxOf(1, ms / 60_000) to Span.MIN
        ms < DAY -> ms / HOUR to Span.HOUR
        ms < 2 * WEEK -> ms / DAY to Span.DAY
        else -> ms / WEEK to Span.WEEK
    }
}
