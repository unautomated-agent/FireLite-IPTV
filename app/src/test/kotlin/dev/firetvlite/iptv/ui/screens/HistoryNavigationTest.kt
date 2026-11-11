package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.TimelineItem
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryNavigationTest {
    private fun time(value: String) = Instant.parse(value).toEpochMilli()
    private val now = time("2026-11-11T20:15:00Z")
    private val items = ChannelTimeline.history(ChannelTimeline.items(emptyList(), now, now - 2 * ChannelTimeline.DAY))

    @Test fun opensAtLiveAndKeepsItAfterEpgReload() {
        val focus = HistoryFocus(now, live = true)
        assertEquals(items.lastIndex, focus.index(items))
        val reloaded = listOf(TimelineItem(items.first().start - 1_000, items.first().start)) + items
        assertEquals(reloaded.lastIndex, focus.index(reloaded))
    }

    @Test fun keepsSelectedTimeRatherThanItsOldIndexWhenRowsChange() {
        val focus = HistoryFocus(items[8].start)
        assertEquals(8, focus.index(items))
        assertEquals(9, focus.index(listOf(TimelineItem(items.first().start - 1_000, items.first().start)) + items))
        assertEquals(7, focus.index(items.filterIndexed { index, _ -> index != 8 }))
        assertEquals(0, focus.index(emptyList()))
    }

    @Test fun dayKeysMoveInTheExpectedDirectionAndClamp() {
        val live = HistoryFocus(now, live = true)
        val yesterday = HistoryNavigation.jumpDay(items, live, -1, now)
        assertEquals(time("2026-11-10T20:00:00Z"), yesterday.time)
        val oldest = HistoryNavigation.jumpDay(items, yesterday, -1, now)
        assertEquals(items.first().start, oldest.time)
        assertEquals(oldest, HistoryNavigation.jumpDay(items, oldest, -1, now))
        assertEquals(live, HistoryNavigation.jumpDay(items, live, 1, now))
        assertEquals(time("2026-11-11T20:00:00Z"), HistoryNavigation.jumpDay(items, yesterday, 1, now).time)
        assertEquals(live, HistoryNavigation.jumpDay(emptyList(), live, -1, now))
        assertEquals(live, HistoryNavigation.jumpDay(items, live, 0, now))
    }

    @Test fun dayJumpUsesWarsawCalendarAcrossDstNotFixed24Hours() {
        val end = time("2026-03-29T12:15:00Z")
        val history = ChannelTimeline.history(ChannelTimeline.items(emptyList(), end, end - 2 * ChannelTimeline.DAY))
        val previous = HistoryNavigation.jumpDay(history, HistoryFocus(end, live = true), -1, end)
        assertEquals(time("2026-03-28T13:00:00Z"), previous.time)
    }

    @Test fun headersUseWarsawDatesAndPolishNames() {
        assertEquals("Dziś", HistoryNavigation.dayLabel(now, now))
        assertEquals("Wczoraj", HistoryNavigation.dayLabel(now - ChannelTimeline.DAY, now))
        assertEquals("Pon 9.11", HistoryNavigation.dayLabel(now - 2 * ChannelTimeline.DAY, now))
        assertEquals("Dziś", HistoryNavigation.dayLabel(time("2026-11-10T23:30:00Z"), time("2026-11-11T00:30:00Z")))
    }
}
