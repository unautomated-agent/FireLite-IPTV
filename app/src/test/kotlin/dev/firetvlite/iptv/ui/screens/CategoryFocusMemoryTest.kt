package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.playback.CategorySwitch
import org.junit.Assert.*
import org.junit.Test

class CategoryFocusMemoryTest {
    private val kino = ChannelFocusTarget("kino", 2)
    private val natura = ChannelFocusTarget("natura", 0)

    @Test fun rapidDoubleRoundTripPreservesAllTargetAndRejectsSupersededCallbacks() {
        val memory = CategoryFocusMemory()
        val groups = listOf(GroupKey.All, GroupKey.Favorites, GroupKey.Recent)
        memory.record(GroupKey.All, memory.generation, kino)
        val requests = listOf(1, 1, -1, -1).map { delta ->
            val next = CategorySwitch.next(groups, memory.selection(GroupKey.All), delta)!!
            memory.begin(next)
        }
        assertEquals(listOf(GroupKey.Favorites, GroupKey.Recent, GroupKey.Favorites, GroupKey.All), requests.map { it.group })
        val restore = requests.last()
        assertEquals(kino, restore.target)
        assertFalse(memory.record(GroupKey.All, 0, natura))
        assertFalse(memory.record(GroupKey.All, restore.generation, natura))
        for (superseded in requests.dropLast(1)) assertFalse(memory.complete(superseded, natura))
        assertTrue(memory.isCurrent(restore))
        assertEquals(kino, memory.target(GroupKey.All))
        assertTrue(memory.complete(restore, kino))
        assertEquals(kino, memory.target(GroupKey.All))
        assertFalse(memory.record(GroupKey.All, requests.first().generation, natura))
        assertTrue(memory.record(GroupKey.All, restore.generation, natura))
    }

    @Test fun singleStepRoundTripAndPagingPositionsStayStable() {
        val memory = CategoryFocusMemory()
        memory.record(GroupKey.All, 0, kino)
        val away = memory.begin(GroupKey.Favorites)
        assertTrue(memory.complete(away, natura))
        val back = memory.begin(GroupKey.All)
        assertEquals(kino, back.target)
        assertTrue(memory.complete(back, kino))
        assertTrue(memory.record(GroupKey.All, back.generation, kino.copy(index = 5)))
        assertEquals(kino.copy(index = 5), memory.target(GroupKey.All))
    }

    @Test fun emptyCategoryCanCompleteWithoutErasingAnotherCategoryTarget() {
        val memory = CategoryFocusMemory()
        memory.record(GroupKey.All, 0, kino)
        val empty = memory.begin(GroupKey.Favorites)
        assertNull(empty.target)
        assertTrue(memory.complete(empty))
        assertNull(memory.target(GroupKey.Favorites))
        assertEquals(kino, memory.target(GroupKey.All))
        assertFalse(memory.complete(empty, natura))
    }
}
