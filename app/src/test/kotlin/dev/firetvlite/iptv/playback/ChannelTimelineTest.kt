package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTimelineTest {
    private val now = 10 * ChannelTimeline.DAY
    private fun programme(start: Long, stop: Long) = ProgrammeEntity(
        sourceId = 1, generation = "test", epgKey = "channel", start = start, stop = stop,
        title = "Programme", description = null, category = null,
    )

    @Test fun catchupLimitOverridesTheShorterLiveWindow() {
        assertEquals(now - 2 * ChannelTimeline.DAY,
            ChannelTimeline.earliest(now, 2, true, now - 1_000))
        assertEquals(now - 1_000, ChannelTimeline.earliest(now, 2, false, now - 1_000))
        assertEquals(null, ChannelTimeline.earliest(now, 0, true, null))
    }

    @Test fun expiredProgrammesAreExcludedAndBoundaryProgrammeStartsAtLimit() {
        val limit = now - ChannelTimeline.HOUR
        val old = programme(limit - 2_000, limit)
        val partial = programme(limit - 1_000, limit + 2_000)
        val items = ChannelTimeline.items(listOf(old, partial), now, limit)
        assertFalse(items.any { it.programme == old })
        assertEquals(limit, items.first().start)
        assertEquals(partial, items.first().programme)
        assertEquals(TimelineAction.Past, ChannelTimeline.action(items.first()))
    }

    @Test fun gapsAreCoveredBySeekableSlotsWithoutOverlappingProgrammes() {
        val limit = now - 3 * ChannelTimeline.HOUR
        val items = ChannelTimeline.items(listOf(programme(limit + 1_000, limit + 2_000)), now, limit)
        val past = items.filterNot { it.live }
        assertEquals(limit, past.first().start)
        assertEquals(now, past.last().stop)
        past.zipWithNext().forEach { (a, b) -> assertEquals(a.stop, b.start) }
    }

    @Test fun noGuideProducesHourlySlotsClippedAtBothEnds() {
        val limit = now - 2 * ChannelTimeline.HOUR - 500
        val items = ChannelTimeline.items(emptyList(), now, limit)
        assertEquals(4, items.size)
        assertEquals(500, items.first().stop - items.first().start)
        assertEquals(now, items[2].stop)
        assertTrue(items.last().live)
    }

    @Test fun noCatchupUsesOnlyTheAvailableHlsWindow() {
        val limit = ChannelTimeline.earliest(now, 0, false, now - 10_000)
        val items = ChannelTimeline.items(listOf(programme(now - 20_000, now - 5_000)), now, limit)
        assertEquals(now - 10_000, items.first().start)
        assertTrue(items.filterNot { it.live }.all { it.start >= now - 10_000 })
    }

    @Test fun noArchiveAndNoGuideShowsOnlyLive() {
        val items = ChannelTimeline.items(emptyList(), now, null)
        assertEquals(1, items.size)
        assertEquals(TimelineAction.Live, ChannelTimeline.action(items.single()))
    }

    @Test fun focusFollowsWatchedTimeAndFutureProgrammeCannotPlay() {
        val past = programme(now - 2_000, now - 1_000)
        val current = programme(now - 1_000, now + 1_000)
        val future = programme(now + 1_000, now + 2_000)
        val items = ChannelTimeline.items(listOf(past, current, future), now, now - 2_000)
        assertEquals(0, ChannelTimeline.focusIndex(items, now - 3_000))
        assertEquals(0, ChannelTimeline.focusIndex(items, now - 1_500))
        assertEquals(1, ChannelTimeline.focusIndex(items, now))
        assertEquals(2, ChannelTimeline.focusIndex(items, future.start))
        assertEquals(TimelineAction.Live, ChannelTimeline.action(items[1]))
        assertEquals(TimelineAction.None, ChannelTimeline.action(items[2]))
        assertTrue(items.last().live)
    }

    @Test fun arrowsClampAtBothEndsIncludingAnEmptyList() {
        assertEquals(0, ChannelTimeline.move(0, -1, 5))
        assertEquals(4, ChannelTimeline.move(4, 1, 5))
        assertEquals(2, ChannelTimeline.move(1, 1, 5))
        assertEquals(0, ChannelTimeline.move(0, -1, 0))
    }

    @Test fun historyIsOldestFirstWithUpcomingBelowLiveAndFutureNotPlayable() {
        val past = programme(now - 2_000, now - 1_000)
        val current = programme(now - 1_000, now + 1_000)
        val future = programme(now + 1_000, now + 2_000)
        val history = ChannelTimeline.history(ChannelTimeline.items(listOf(past, current, future), now, now - 2_000))
        assertTrue(history[2].live)
        assertEquals(current, history[2].programme)
        assertEquals(listOf(past, current), history.take(2).map { it.programme })
        assertEquals(future, history.last().programme)
        assertEquals(TimelineAction.None, ChannelTimeline.action(history.last()))
        assertEquals(TimelineAction.Past, ChannelTimeline.action(history[1]))
        val liveOnly = ChannelTimeline.history(ChannelTimeline.items(listOf(current, future), now, null), false)
        assertTrue(liveOnly.first().live)
        assertTrue(liveOnly.last().future)
        assertEquals(2, liveOnly.size)
        assertEquals(1, ChannelTimeline.historyFocusIndex(history, now))
        assertEquals(0, ChannelTimeline.historyFocusIndex(history, now - 1_500))
        assertEquals(0, ChannelTimeline.historyFocusIndex(history, now - 3_000))
        assertEquals(3, ChannelTimeline.move(2, 1, history.size))
        assertEquals(0, ChannelTimeline.move(0, -1, history.size))
        assertEquals(3, ChannelTimeline.historyFocusIndex(history, now + 5_000))
    }

    @Test fun everyKnownUpcomingProgrammeIsSortedBelowLiveEvenBeyondThreeDays() {
        val first = programme(now + 1_000, now + 2_000)
        val second = programme(now + ChannelTimeline.DAY, now + ChannelTimeline.DAY + 2_000)
        val last = programme(now + 5 * ChannelTimeline.DAY, now + 5 * ChannelTimeline.DAY + 2_000)
        val invalid = programme(now + 6 * ChannelTimeline.DAY, now + 5 * ChannelTimeline.DAY)
        val history = ChannelTimeline.history(ChannelTimeline.items(listOf(last, invalid, second, first), now, null), false)
        assertTrue(history.first().live)
        assertEquals(listOf(first, second, last), history.drop(1).map { it.programme })
        assertTrue(history.drop(1).all { it.future && ChannelTimeline.action(it) == TimelineAction.None })
    }

    @Test fun emptyAndGuideLessHistoryHaveSafeFocus() {
        assertTrue(ChannelTimeline.history(emptyList()).isEmpty())
        assertEquals(0, ChannelTimeline.historyFocusIndex(emptyList(), now))
        val history = ChannelTimeline.history(ChannelTimeline.items(emptyList(), now, null))
        assertTrue(history.single().live)
        assertEquals(0, ChannelTimeline.historyFocusIndex(history, now - 1_000))
    }

    @Test fun conflictingFutureIntervalsWithTheSameStartKeepOneStableRow() {
        val first = programme(now + 1_000, now + 2_000)
        val conflicting = first.copy(stop = now + 3_000, title = "Conflicting guide entry")
        val history = ChannelTimeline.history(ChannelTimeline.items(listOf(first, conflicting), now, null), false)
        assertEquals(2, history.size)
        assertEquals(first, history.last().programme)
        assertEquals(TimelineAction.None, ChannelTimeline.action(history.last()))
    }

    @Test fun invalidIntervalsAreIgnoredAndNoArchiveKeepsOnlyCurrentNextAndLive() {
        val invalid = programme(now - 3_000, now - 4_000)
        val expired = programme(now - 2_000, now - 1_000)
        val current = programme(now - 1_000, now + 1_000)
        val next = programme(now + 1_000, now + 2_000)
        val items = ChannelTimeline.items(listOf(invalid, expired, current, next), now, null)
        assertEquals(3, items.size)
        assertEquals(current, items.first().programme)
        assertTrue(items[1].future)
        assertTrue(items.last().live)
    }
}
