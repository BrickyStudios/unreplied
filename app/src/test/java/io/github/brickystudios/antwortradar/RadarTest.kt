package io.github.brickystudios.antwortradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RadarTest {
    private val key = Radar.key("com.whatsapp", "chat1")
    private fun msg(text: String, time: Long, group: Boolean = false, fromMe: Boolean = false) =
        Incoming(key, "Lisa", text, group, fromMe, time)

    @Test fun newMessageIsUnread() {
        val e = Radar.posted(null, msg("Hast du die Aufgaben?", 100), emptyList())!!
        assertEquals(State.UNREAD, e.state)
        assertEquals(100, e.since)
        assertEquals("com.whatsapp", e.app)
    }

    @Test fun openedChatBecomesRead() {
        val e = Radar.posted(null, msg("Hi", 100), emptyList())
        assertEquals(State.READ, Radar.removed(e, 8, 200)!!.state)
        assertEquals(200, Radar.removed(e, 8, 200)!!.readAt)
    }

    @Test fun systemRemovalIsNotRead() {
        val e = Radar.posted(null, msg("Hi", 100), emptyList())
        assertEquals(State.UNREAD, Radar.removed(e, 10 /* REASON_LISTENER_CANCEL */, 200)!!.state)
    }

    @Test fun repostOfOldMessageKeepsRead() {
        val read = Radar.removed(Radar.posted(null, msg("Hi", 100), emptyList()), 8, 200)!!
        assertSame(read, Radar.posted(read, msg("Hi", 100), emptyList()))
    }

    @Test fun newMessageAfterReadKeepsWaitingSince() {
        val read = Radar.removed(Radar.posted(null, msg("Hi", 100), emptyList()), 8, 200)!!
        val again = Radar.posted(read, msg("Hallo??", 300), emptyList())!!
        assertEquals(State.UNREAD, again.state)
        assertEquals(100, again.since)
        assertEquals("Hallo??", again.preview)
    }

    @Test fun ownReplyInNotificationIsDone() {
        val e = Radar.posted(null, msg("Hi", 100), emptyList())
        val done = Radar.posted(e, msg("Klar!", 150, fromMe = true), emptyList())!!
        assertEquals(State.DONE, done.state)
        assertNull(Radar.posted(null, msg("Klar!", 150, fromMe = true), emptyList()))
    }

    @Test fun answeredChatStartsFreshOnNextMessage() {
        val done = Radar.done(Radar.posted(null, msg("Hi", 100), emptyList())!!, 150)
        val next = Radar.posted(done, msg("Und morgen?", 400), emptyList())!!
        assertEquals(State.UNREAD, next.state)
        assertEquals(400, next.since)
    }

    @Test fun groupsOnlyWhenMentioned() {
        assertNull(Radar.posted(null, msg("Wer hat Folien?", 100, group = true), listOf("Alex")))
        val e = Radar.posted(null, msg("@alex kannst du die Folien?", 100, group = true), listOf("Alex"))
        assertEquals(State.UNREAD, e!!.state)
        assertNull(Radar.posted(null, msg("@Alex hi", 100, group = true), listOf(" ")))
    }

    @Test fun ignoredChatsAreNeverTracked() {
        assertNull(Radar.posted(null, msg("Hi", 100), emptyList(), ignored = setOf(key)))
    }

    @Test fun previewIsCut() {
        val e = Radar.posted(null, msg("x".repeat(500), 100), emptyList())!!
        assertEquals(Radar.PREVIEW, e.preview.length)
    }

    @Test fun cleanNameDropsCounter() {
        assertEquals("Lisa", Radar.cleanName("Lisa (2 Nachrichten)"))
        assertEquals("Projekt 2026", Radar.cleanName("Projekt 2026 (3 messages)"))
        assertEquals("Team (Uni)", Radar.cleanName("Team (Uni)"))
    }

    @Test fun snoozedChatsLeaveTheListUntilTheirTime() {
        val read = Radar.removed(Radar.posted(null, msg("Hi", 100), emptyList()), 8, 200)!!
        val later = Radar.snooze(read, 1_000)
        assertTrue(Radar.waiting(listOf(later), 500).isEmpty())
        assertEquals(listOf(later), Radar.snoozed(listOf(later), 500))
        assertEquals(listOf(later), Radar.waiting(listOf(later), 1_000))
        // a new message brings it back at once
        assertEquals(0, Radar.posted(later, msg("??", 600), emptyList())!!.snoozeUntil)
        assertEquals(0, Radar.done(later, 700).snoozeUntil)
    }

    @Test fun waitingIsOldestFirstAndPruneDropsOldDone() {
        val a = Entry("w|a", "A", "", false, since = 300, last = 300, state = State.READ)
        val b = Entry("w|b", "B", "", false, since = 100, last = 100, state = State.READ)
        val c = Entry("w|c", "C", "", false, since = 0, last = 0, state = State.DONE, doneAt = 0)
        assertEquals(listOf("w|b", "w|a"), Radar.waiting(listOf(a, b, c), 1_000).map { it.key })
        assertEquals(listOf("w|a", "w|b"), Radar.prune(listOf(a, b, c), 31 * Radar.DAY).map { it.key })
    }

    @Test fun quietFavoritesOnly() {
        val now = 10 * Radar.WEEK
        val old = Radar.touch(null, "w|l", "Lisa", now - 4 * Radar.WEEK).copy(favorite = true)
        val fresh = Radar.touch(null, "w|t", "Tim", now - Radar.WEEK).copy(favorite = true)
        val notFav = Radar.touch(null, "w|m", "Mia", now - 8 * Radar.WEEK)
        assertEquals(listOf("w|l"), Radar.quiet(listOf(old, fresh, notFav), now, 3).map { it.key })
        // touching keeps the favorite flag and never goes back in time
        val touched = Radar.touch(old, "w|l", "Lisa", now)
        assertTrue(touched.favorite)
        assertSame(touched, Radar.touch(touched, "w|l", "Lisa", now - 1))
    }

    @Test fun laterTimes() {
        fun at(day: Int, hour: Int, min: Int = 0) = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, day, hour, min, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (in3h, evening, morning) = Radar.laterTimes(at(10, 14), 19)
        assertEquals(at(10, 17), in3h)
        assertEquals(at(10, 19), evening)
        assertEquals(at(11, 9), morning)
        // at 18:30 "this evening" is too close: tomorrow evening instead
        assertEquals(at(11, 19), Radar.laterTimes(at(10, 18, 30), 19)[1])
    }

    @Test fun ageSpans() {
        assertEquals(1L to Span.MIN, Radar.age(5_000))
        assertEquals(5L to Span.HOUR, Radar.age(5 * Radar.HOUR))
        assertEquals(3L to Span.DAY, Radar.age(3 * Radar.DAY + 5))
        assertEquals(4L to Span.WEEK, Radar.age(4 * Radar.WEEK))
    }
}
