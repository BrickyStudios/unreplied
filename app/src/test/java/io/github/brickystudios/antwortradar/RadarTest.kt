package io.github.brickystudios.antwortradar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RadarTest {
    private fun msg(text: String, time: Long, group: Boolean = false, fromMe: Boolean = false) =
        Incoming("chat1", "Lisa", text, group, fromMe, time)

    @Test fun newMessageIsUnread() {
        val e = Radar.posted(null, msg("Hast du die Aufgaben?", 100), emptyList())!!
        assertEquals(State.UNREAD, e.state)
        assertEquals(100, e.since)
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

    @Test fun previewIsCut() {
        val e = Radar.posted(null, msg("x".repeat(500), 100), emptyList())!!
        assertEquals(Radar.PREVIEW, e.preview.length)
    }

    @Test fun cleanNameDropsCounter() {
        assertEquals("Lisa", Radar.cleanName("Lisa (2 Nachrichten)"))
        assertEquals("Projekt 2026", Radar.cleanName("Projekt 2026 (3 messages)"))
        assertEquals("Team (Uni)", Radar.cleanName("Team (Uni)"))
    }

    @Test fun waitingIsOldestFirstAndPruneDropsOldDone() {
        val a = Entry("a", "A", "", false, since = 300, last = 300, state = State.READ)
        val b = Entry("b", "B", "", false, since = 100, last = 100, state = State.READ)
        val c = Entry("c", "C", "", false, since = 0, last = 0, state = State.DONE, doneAt = 0)
        assertEquals(listOf("b", "a"), Radar.waiting(listOf(a, b, c)).map { it.key })
        assertEquals(listOf("a", "b"), Radar.prune(listOf(a, b, c), 31 * Radar.DAY).map { it.key })
    }

    @Test fun ageReadsLikeASketch() {
        assertEquals("1 Min", Radar.age(5_000))
        assertEquals("5 Std", Radar.age(5 * Radar.HOUR))
        assertEquals("3 T", Radar.age(3 * Radar.DAY + 5))
    }
}
