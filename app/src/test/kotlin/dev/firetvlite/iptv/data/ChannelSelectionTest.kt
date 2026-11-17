package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.library.ChannelSelection
import dev.firetvlite.iptv.data.library.QueryZapContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChannelSelectionTest {
    private val a = ChannelEntity(1, 1, 0, "A", "", "https://example.org/a", null, null, null, null, 0)
    private val b = a.copy(id = 2, name = "B", streamUrl = "https://example.org/b", position = 1)

    @Test fun reorderedPlaylistPlaysSelectedIdentityAndKeepsThatRowAfterResolution(): Unit = runBlocking {
        var rows = listOf(a, b)
        val expected = rows.first()
        val fresh = a.copy(name = "A refreshed", position = 1, catchupDays = 7)
        rows = listOf(b.copy(position = 0), fresh)
        val zap = QueryZapContext({ rows.size }, { rows.getOrNull(it) })
        val selected = ChannelSelection.resolve(expected, zap) { rows.indexOfFirst { it.id == expected.id } }
        rows = listOf(a, b)
        assertEquals(fresh, selected!!.channel)
        assertEquals(1, selected.index)
        assertEquals(2, selected.count)
        assertEquals(expected.streamUrl, selected.channel.streamUrl)
    }

    @Test fun missingAndEmptyPlaylistsDoNotSelectAnotherChannel(): Unit = runBlocking {
        for (rows in listOf(emptyList(), listOf(b))) {
            val zap = QueryZapContext({ rows.size }, { rows.getOrNull(it) })
            assertNull(ChannelSelection.resolve(a, zap) { rows.indexOfFirst { it.id == a.id }.takeIf { it >= 0 } })
            assertNull(ChannelSelection.resolve(a, zap) { 0 })
        }
    }

    @Test fun reusedIdChangedUrlOrWrongSourceCannotReplaceTheChosenChannel(): Unit = runBlocking {
        for (row in listOf(b, a.copy(sourceId = 2), a.copy(streamUrl = b.streamUrl))) {
            val zap = QueryZapContext({ 1 }, { row })
            assertNull(ChannelSelection.resolve(a, zap) { 0 })
        }
    }
}
