package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.playback.CategorySwitch
import org.junit.Assert.*
import org.junit.Test

class CategoryStripTest {
    private val groups = listOf(GroupKey.All, GroupKey.Favorites, GroupKey.Recent)

    @Test fun stripFollowsBothDirectionsAndWrapsWithoutChangingFocusMemory() {
        val memory = CategoryFocusMemory()
        val target = ChannelFocusTarget("remembered", 7)
        memory.record(GroupKey.All, 0, target)
        assertEquals(listOf(GroupKey.Recent, GroupKey.All, GroupKey.Favorites), categoryStrip(groups, GroupKey.All))
        for (delta in listOf(1, -1)) {
            val selected = CategorySwitch.next(groups, GroupKey.All, delta)!!
            val strip = categoryStrip(groups, selected)
            assertEquals(selected, strip[1])
            assertEquals(CategorySwitch.next(groups, selected, -1), strip[0])
            assertEquals(CategorySwitch.next(groups, selected, 1), strip[2])
        }
        assertEquals(0L, memory.generation)
        assertEquals(target, memory.target(GroupKey.All))
    }

    @Test fun emptySingletonAndRemovedGroupsKeepCurrentVisible() {
        assertEquals(listOf(GroupKey.All), categoryStrip(emptyList(), GroupKey.All))
        assertEquals(listOf(GroupKey.All), categoryStrip(listOf(GroupKey.All), GroupKey.All))
        assertEquals(listOf(GroupKey.Named("removed")), categoryStrip(groups, GroupKey.Named("removed")))
    }
}
