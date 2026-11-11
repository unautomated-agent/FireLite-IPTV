package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ChannelIdentity
import dev.firetvlite.iptv.data.playlist.PlaylistIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistIdentityTest {
    private fun channel(url: String) = ChannelEntity(sourceId = 1, position = 0, name = url, groupName = "",
        streamUrl = url, logoUrl = null, tvgId = null, catchupType = null, catchupSource = null, catchupDays = 3)

    @Test fun reorderedChannelsKeepTheirIdsAndUpdatedFieldsWhileNewChannelsGetNewIds() {
        val rows = PlaylistIdentity.retain(listOf(channel("b"), channel("a"), channel("c")),
            listOf(ChannelIdentity(11, "a"), ChannelIdentity(12, "b"), ChannelIdentity(13, "removed")))
        assertEquals(listOf(12L, 11L, 0L), rows.map { it.id })
        assertEquals(listOf(3, 3, 3), rows.map { it.catchupDays })
    }

    @Test fun duplicateUrlsNeverReuseAnIdTwice() {
        val rows = PlaylistIdentity.retain(List(3) { channel("a") },
            listOf(ChannelIdentity(11, "a"), ChannelIdentity(12, "a")))
        assertEquals(listOf(11L, 12L, 0L), rows.map { it.id })
    }

    @Test fun emptyOrNewPlaylistsDoNotBorrowOtherIdentities() {
        assertEquals(emptyList<ChannelEntity>(), PlaylistIdentity.retain(emptyList(), listOf(ChannelIdentity(1, "a"))))
        assertEquals(0L, PlaylistIdentity.retain(listOf(channel("a")), emptyList()).single().id)
    }
}
