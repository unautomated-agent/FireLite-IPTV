package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey
import org.junit.Assert.*
import org.junit.Test

class GuideFocusMemoryTest {
    @Test fun rewindAndFastForwardChoosePanesOnlyOnTheFirstKeyDown() {
        assertEquals(GuideFocusPane.Groups, GuideFocusMemory.destination(false, 0))
        assertEquals(GuideFocusPane.Grid, GuideFocusMemory.destination(true, 0))
        for (repeat in listOf(1, 2, 20)) {
            assertNull(GuideFocusMemory.destination(false, repeat))
            assertNull(GuideFocusMemory.destination(true, repeat))
        }
    }

    @Test fun eachGroupRemembersItsAbsoluteCellTimeWindowAndChannelNameMode() {
        val memory = GuideFocusMemory()
        val all = GuideCellFocus(10 * GuideTimeline.HOUR, 11 * GuideTimeline.HOUR, true)
        val films = GuideCellFocus(20 * GuideTimeline.HOUR, 21 * GuideTimeline.HOUR, false)
        val filmGroup = GroupKey.Named("Filmy")
        memory.save(GroupKey.All, all)
        memory.save(filmGroup, films)
        repeat(3) {
            assertEquals(all, memory.cell(GroupKey.All, 30 * GuideTimeline.HOUR))
            assertEquals(films, memory.cell(filmGroup, 30 * GuideTimeline.HOUR))
        }
    }

    @Test fun anUnvisitedOrEmptyGroupStartsAtNowWithoutChangingOtherMemories() {
        val memory = GuideFocusMemory()
        val all = GuideCellFocus(0, 100, true)
        memory.save(GroupKey.All, all)
        val now = GuideTimeline.HOUR + 1234
        assertEquals(GuideCellFocus(GuideTimeline.HOUR, now, true), memory.cell(GroupKey.Favorites, now))
        assertEquals(all, memory.cell(GroupKey.All, now))
    }

    @Test fun groupRoundTripsKeepCellTimeWhilePagingResolvesTheRememberedChannelPosition() {
        val rows = CategoryFocusMemory()
        val cells = GuideFocusMemory()
        val kino = ChannelFocusTarget("kino", 12)
        val cell = GuideCellFocus(10 * GuideTimeline.HOUR, 11 * GuideTimeline.HOUR, true)
        rows.record(GroupKey.All, rows.generation, kino)
        cells.save(GroupKey.All, cell)
        rows.cancel()
        assertFalse(rows.record(GroupKey.All, 0, ChannelFocusTarget("first", 0)))
        val pending = rows.begin(GroupKey.All)
        assertEquals(15, pending.target!!.index(15, 40))
        assertTrue(rows.complete(pending, kino.copy(index = 15)))
        assertEquals(kino.copy(index = 15), rows.target(GroupKey.All))
        assertEquals(cell, cells.cell(GroupKey.All, 30 * GuideTimeline.HOUR))
    }
}
