package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.FilmShowing
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ListZapContext
import dev.firetvlite.iptv.data.library.ZapContext
import dev.firetvlite.iptv.ui.screens.openFilmChannel
import dev.firetvlite.iptv.ui.screens.openArchivedFilm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FilmPlaybackTest {
    private fun channel(id: Long) = ChannelEntity(id = id, sourceId = 1, name = "Channel $id",
        streamUrl = "http://fixture/$id", position = id.toInt(), groupName = "Films", logoUrl = null,
        tvgId = null, catchupType = null, catchupSource = null, catchupDays = 0)

    @Test fun filmSelectionRetainsAllChannelsAndItsActualPlaylistIndex() = runBlocking {
        val channels = listOf(channel(1), channel(2), channel(3))
        val all = ListZapContext(channels)
        var opened: ZapContext? = null
        var index = -1
        openFilmChannel(channels[1], { id ->
            channels.indexOfFirst { it.id == id }
        }, { group -> assertEquals(GroupKey.All, group); all }, { zap, start, group ->
            assertEquals(GroupKey.All, group); opened = zap; index = start
        })
        assertSame(all, opened)
        assertEquals(1, index)
        assertEquals(3, opened!!.count())
        assertEquals(channels[2], opened!!.at(index + 1))
        assertEquals(channels[0], opened!!.at(index - 1))
    }

    @Test fun missingChannelOrChangingPlaylistCannotOpenAnotherStream() = runBlocking {
        val selected = channel(1)
        val open: (ZapContext, Int, GroupKey) -> Unit = { _, _, _ -> fail("Unexpected playback") }
        openFilmChannel(selected, { null }, { ListZapContext(listOf(selected)) }, open)
        openFilmChannel(selected, { 0 }, { null }, open)
        openFilmChannel(selected, { 0 }, { ListZapContext(emptyList()) }, open)
        openFilmChannel(selected, { 0 }, { ListZapContext(listOf(channel(2))) }, open)
        openFilmChannel(selected, { 0 }, { ListZapContext(listOf(selected.copy(sourceId = 2))) }, open)
    }

    @Test fun duplicateUrlsRetainTheSelectedStableChannelId() = runBlocking {
        val first = channel(1)
        val selected = channel(2).copy(streamUrl = first.streamUrl)
        val channels = listOf(first, selected)
        var opened = false
        openFilmChannel(selected, { id -> channels.indexOfFirst { it.id == id } }, { ListZapContext(channels) },
            { zap, index, _ -> assertEquals(1, index); assertEquals(selected, channels[index]); opened = true })
        assertTrue(opened)
        openFilmChannel(selected, { 0 }, { ListZapContext(channels) }, { _, _, _ -> fail("Opened URL duplicate") })
    }

    private val now = 10 * Films.DAY
    private fun archive() = FilmShowing(channel(2).copy(catchupType = "shift", catchupDays = 2),
        ProgrammeEntity(sourceId = 1, generation = "g", epgKey = "c", start = now - Films.DAY,
            stop = now - Films.DAY + 7_200_000, title = "Film", description = null, category = "Film"), null)

    @Test fun archiveSelectionRetainsZapContextAndStartsAtTheProgrammeStart() = runBlocking {
        val selected = archive()
        val all = ListZapContext(listOf(channel(1), selected.channel, channel(3)))
        var opened = false
        openArchivedFilm(selected, { 1 }, { all }, { zap, index, group, start ->
            assertSame(all, zap)
            assertEquals(1, index)
            assertEquals(GroupKey.All, group)
            assertEquals(selected.programme.start, start)
            opened = true
        }, { now })
        assertTrue(opened)
    }

    @Test fun expiredFutureUnsupportedOrChangedPlaylistArchivesCannotOpen() = runBlocking {
        val film = archive()
        val open: (ZapContext, Int, GroupKey, Long) -> Unit = { _, _, _, _ -> fail("Unexpected archive playback") }
        for (invalid in listOf(film.copy(channel = film.channel.copy(catchupDays = 0)),
            film.copy(programme = film.programme.copy(start = now - 3 * Films.DAY)),
            film.copy(programme = film.programme.copy(start = now + 1, stop = now + 7_200_000)))) {
            openArchivedFilm(invalid, { 0 }, { ListZapContext(listOf(invalid.channel)) }, open, { now })
        }
        openArchivedFilm(film, { 0 }, { ListZapContext(listOf(film.channel.copy(sourceId = 2))) }, open, { now })
        openArchivedFilm(film, { null }, { ListZapContext(emptyList()) }, open, { now })
    }

    @Test fun expiryWhileResolvingTheChannelDoesNotOpenAnArchive() = runBlocking {
        val film = archive()
        var current = film.programme.start + 2 * Films.DAY
        openArchivedFilm(film, { current++; 0 }, { ListZapContext(listOf(film.channel)) },
            { _, _, _, _ -> fail("Opened expired archive") }, { current })
    }
}
