package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.library.ChannelTabPolicy
import dev.firetvlite.iptv.data.library.GroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTabPolicyTest {
    @Test fun automaticGroupFocusCannotOverridePendingRowRestoration() {
        assertFalse(ChannelTabPolicy.canSelectGroup(2, null))
        assertFalse(ChannelTabPolicy.canSelectGroup(2, 1))
        assertTrue(ChannelTabPolicy.canSelectGroup(2, 2))
        assertTrue(ChannelTabPolicy.canSelectGroup(null, null))
    }
    @Test fun returnsToThePlayedSectionEvenWhenTheChannelAlsoBecameRecent() {
        val sections = listOf(GroupKey.Recent to listOf("channel"), GroupKey.Favorites to listOf("channel"))
        assertEquals(1, ChannelTabPolicy.homeSection(sections, GroupKey.Favorites, "channel"))
        assertEquals(0, ChannelTabPolicy.homeSection(sections, GroupKey.Recent, "channel"))
    }

    @Test fun removedRowsFallBackToAnAvailableSection() {
        val sections = listOf(GroupKey.Recent to emptyList<String>(), GroupKey.Favorites to listOf("other"))
        assertEquals(1, ChannelTabPolicy.homeSection(sections, GroupKey.Recent, "missing"))
        assertEquals(1, ChannelTabPolicy.homeSection(sections, null, null))
    }

    @Test fun emptyPlaylistHasNoRowToFocus() {
        assertEquals(-1, ChannelTabPolicy.homeSection(emptyList(), null, null))
        assertEquals(-1, ChannelTabPolicy.homeSection(listOf(GroupKey.Recent to emptyList()), GroupKey.All, "missing"))
    }
}
