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
