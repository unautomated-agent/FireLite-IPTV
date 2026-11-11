package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.library.ChannelTabPolicy
import dev.firetvlite.iptv.data.library.GroupKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelTabPolicyTest {
    @Test fun removingMiddleFavouritesKeepsTheirPositionRatherThanReturningToThePlayedRow() {
        assertEquals(2, ChannelTabPolicy.collectionFocusIndex(4, 2, null, true, false))
        assertEquals(2, ChannelTabPolicy.collectionFocusIndex(3, 2, 0, true, false))
        assertEquals(1, ChannelTabPolicy.collectionFocusIndex(2, 2, 0, true, false))
    }

    @Test fun removingLastFavouriteUsesPreviousRowOrEmptyState() {
        assertEquals(3, ChannelTabPolicy.collectionFocusIndex(4, 4, null, true, false))
        assertEquals(-1, ChannelTabPolicy.collectionFocusIndex(0, 0, null, true, false))
    }

    @Test fun countRefreshDoesNotRepeatInitialOrPlaybackReturnFocus() {
        assertNull(ChannelTabPolicy.collectionFocusIndex(4, null, null, true, false))
        assertNull(ChannelTabPolicy.collectionFocusIndex(4, null, 0, true, false))
        assertEquals(2, ChannelTabPolicy.collectionFocusIndex(5, null, 2, false, false))
        assertEquals(0, ChannelTabPolicy.collectionFocusIndex(5, null, null, false, false))
        assertEquals(-1, ChannelTabPolicy.collectionFocusIndex(0, null, null, false, false))
        assertEquals(-1, ChannelTabPolicy.collectionFocusIndex(5, null, null, false, true))
    }

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
