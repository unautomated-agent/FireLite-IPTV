package dev.firetvlite.iptv.ui

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.ui.screens.GuideAction
import dev.firetvlite.iptv.ui.screens.GuideTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTimelineTest {
    private val now = 1_800_000_000_000L
    private fun programme(start: Long, stop: Long) = ProgrammeEntity(
        sourceId = 1, generation = "test", epgKey = "channel", start = start, stop = stop,
        title = "Programme", description = null, category = null,
    )

    @Test fun boundsUseGroupRetentionAndAvailableFutureDataWithCaps() {
        val bounds = GuideTimeline.bounds(now, 30, now + 10 * GuideTimeline.DAY)
        assertEquals(now - 7 * GuideTimeline.DAY, bounds.earliest)
        assertEquals(now + 3 * GuideTimeline.DAY, bounds.latest)
        assertEquals(now, GuideTimeline.bounds(now, 0, now - 1).earliest)
        assertEquals(now + 60_000, GuideTimeline.bounds(now, 1, now + 60_000).latest)
    }

    @Test fun windowCannotScrollPastEitherBoundary() {
        val bounds = GuideTimeline.bounds(now, 1, now + GuideTimeline.DAY)
        assertEquals(bounds.earliest, GuideTimeline.windowStart(Long.MIN_VALUE, bounds))
        assertEquals(bounds.latest - GuideTimeline.WINDOW, GuideTimeline.windowStart(Long.MAX_VALUE, bounds))
        val short = GuideTimeline.bounds(now, 0, now + 1_000)
        assertEquals(now, GuideTimeline.windowStart(now + GuideTimeline.HOUR, short))
    }

    @Test fun cellGeometryClipsBothEdgesAndFillsMissingIntervals() {
        val cells = GuideTimeline.cells(listOf(programme(now - 1_000, now + 60_000),
            programme(now + 120_000, now + 2 * GuideTimeline.WINDOW)), now)
        assertEquals(3, cells.size)
        assertEquals(now, cells.first().start)
        assertEquals(now + GuideTimeline.WINDOW, cells.last().stop)
        assertEquals(null, cells[1].programme)
        assertEquals(1f, cells.sumOf { (GuideTimeline.fraction(it.stop, now) - GuideTimeline.fraction(it.start, now)).toDouble() }.toFloat(), 0.00001f)
        assertTrue(cells.all { it.stop > it.start })
    }

    @Test fun emptyAndInvalidGuidesHaveOneWideFocusableGap() {
        assertEquals(1, GuideTimeline.cells(emptyList(), now).size)
        assertEquals(null, GuideTimeline.cells(listOf(programme(now + 10, now)), now).single().programme)
    }

    @Test fun verticalFocusPreservesTheAbsoluteTimeAcrossDifferentSchedules() {
        val time = now + 90_000
        val first = GuideTimeline.cells(listOf(programme(now, now + 100_000)), now)
        val second = GuideTimeline.cells(listOf(programme(now, now + 60_000), programme(now + 60_000, now + 120_000)), now)
        assertEquals(0, GuideTimeline.focusIndex(first, time))
        assertEquals(1, GuideTimeline.focusIndex(second, time))
        assertEquals(1, GuideTimeline.focusIndex(second, now + 60_000))
        assertEquals(0, GuideTimeline.focusIndex(second, now - 1))
        assertEquals(second.lastIndex, GuideTimeline.focusIndex(second, now + GuideTimeline.WINDOW))
    }

    @Test fun horizontalNavigationLeavesClippedProgrammesRatherThanSelectingThemAgain() {
        val long = programme(now - GuideTimeline.HOUR, now + 4 * GuideTimeline.HOUR)
        val cell = GuideTimeline.cells(listOf(long), now).single()
        val previous = GuideTimeline.adjacentTime(cell, false)
        val next = GuideTimeline.adjacentTime(cell, true)
        assertEquals(long.start - 1, previous)
        assertEquals(long.stop, next)
        val bounds = GuideTimeline.bounds(now, 1, now + GuideTimeline.DAY)
        for (time in listOf(previous, next)) {
            val start = GuideTimeline.reveal(now, time, bounds)
            assertTrue(time >= start && time < start + GuideTimeline.WINDOW)
        }
        assertEquals(bounds.earliest, GuideTimeline.reveal(bounds.earliest, bounds.earliest - 1, bounds))
    }

    @Test fun onlyCurrentOrRetainedSupportedPastProgrammesArePlayable() {
        assertEquals(GuideAction.Live, GuideTimeline.action(programme(now - 1, now + 1), now, 0, false))
        val past = programme(now - 60_000, now)
        assertEquals(GuideAction.Archive, GuideTimeline.action(past, now, 1, true))
        assertEquals(GuideAction.Details, GuideTimeline.action(past, now, 1, false))
        assertEquals(GuideAction.Details, GuideTimeline.action(past, now, 0, true))
        assertEquals(GuideAction.Details, GuideTimeline.action(programme(now - GuideTimeline.DAY - 1, now - 1), now, 1, true))
        assertEquals(GuideAction.Details, GuideTimeline.action(programme(now + 1, now + 60_000), now, 1, true))
        assertEquals(GuideAction.Live, GuideTimeline.action(null, now, 0, false))
    }
}
