package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.SearchDao
import dev.firetvlite.iptv.data.db.SearchProgramme
import dev.firetvlite.iptv.playback.ChannelTimeline
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalSearchTest {
    private val now = 1_800_000_000_000L
    private val day = ChannelTimeline.DAY
    private fun channel(id: Long = 1, name: String = "Łódź", source: Long = 1, days: Int = 7,
        type: String? = "shift") = ChannelEntity(id, source, id.toInt(), name, "", "https://example.test/live/$id",
        null, null, type, null, days)
    private fun programme(id: Long = 1, start: Long = now - 1_000, stop: Long = now + 1_000,
        channel: ChannelEntity = channel(), title: String = "Wiadomości") = SearchProgramme(channel, id, title, start, stop)

    @Test fun matchingIgnoresCaseAndDiacriticsIncludingPolishLStroke() {
        for ((query, name) in listOf("republika" to "Republika", "tvn" to "TVN 24", "lodz" to "Łódź",
            "zazolc gesla jazn" to "ZAŻÓŁĆ GĘŚLĄ JAŹŃ", "cafe" to "Cafe\u0301")) {
            assertTrue(name, SearchText.matches(name, SearchText.normalize(query)))
        }
        assertFalse(SearchText.matches("TVN 24", "tvp"))
        assertFalse(SearchText.matches("TVN 24", ""))
        assertFalse(SearchText.matches("TVN 24", "%"))
        assertEquals("tvn 24", SearchText.normalize("  TVN\t 24  "))
    }

    @Test fun normalizationDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("imieniny", SearchText.normalize("IMIENINY"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun programmeWindowIncludesLiveUpcomingAndOnlyAvailableArchive() {
        assertTrue(SearchWindow.includes(programme(), now))
        assertTrue(SearchWindow.includes(programme(start = now + day - 1, stop = now + day + 1_000), now))
        assertFalse(SearchWindow.includes(programme(start = now + day, stop = now + day + 1_000), now))
        val oldest = programme(start = now - 7 * day, stop = now - 7 * day + 1_000)
        assertTrue(SearchWindow.includes(oldest, now))
        assertFalse(SearchWindow.includes(oldest.copy(start = oldest.start - 1), now))
        assertFalse(SearchWindow.includes(oldest.copy(channel = channel(days = 0)), now))
        assertFalse(SearchWindow.includes(oldest.copy(channel = channel(type = "unknown")), now))
        assertTrue(SearchWindow.includes(programme(channel = channel(days = 0)), now))
        assertFalse(SearchWindow.includes(programme(stop = now - 1_000), now))
    }

    @Test fun liveAndUpcomingSelectLiveOnlyPastSelectsArchiveStart() {
        assertNull(SearchWindow.archiveStart(programme(), now))
        assertNull(SearchWindow.archiveStart(programme(start = now + 1_000, stop = now + 2_000), now))
        val past = programme(start = now - 3_000, stop = now)
        assertEquals(past.start, SearchWindow.archiveStart(past, now))
        assertNull(SearchWindow.archiveStart(past.copy(channel = channel(days = 0)), now))
    }

    @Test fun emptyQueryDoesNotTouchRoomAndMissingMatchesAreEmpty() = runBlocking {
        val dao = FakeDao(listOf(channel()), listOf(programme()))
        assertEquals(SearchResults(), LocalSearch(dao).search(1, " \t ", now))
        assertEquals(0, dao.calls)
        assertEquals(SearchResults(), LocalSearch(dao).search(1, "missing", now))
        assertTrue(dao.calls > 0)
    }

    @Test fun searchPagesBeyondNonMatchesAndCapsEachGroupWithoutMixingSources() = runBlocking {
        val channels = (1L..260).map { channel(it, if (it > 200) "Łódź $it" else "other") } +
            channel(261, "Łódź wrong source", source = 2)
        val programmes = (1L..260).map { programme(it, start = now + it,
            stop = now + it + 1_000, title = if (it > 200) "ŁÓDŹ $it" else "other") }
        val dao = FakeDao(channels, programmes)
        val result = LocalSearch(dao).search(1, "lodz", now)
        assertEquals(50, result.channels.size)
        assertEquals(50, result.programmes.size)
        assertEquals(201L, result.channels.first().id)
        assertEquals(250L, result.channels.last().id)
        assertEquals(201L, result.programmes.first().programmeId)
        assertEquals(250L, result.programmes.last().programmeId)
        assertEquals(4, dao.calls)
        assertTrue(dao.threads.all { it != Thread.currentThread().name })
    }

    @Test fun currentAndUpcomingPrecedePastAndUnavailableArchiveIsFiltered() = runBlocking {
        val unavailable = programme(4, now - 5_000, now - 4_000, channel(days = 0))
        val past = programme(3, now - 3_000, now - 2_000)
        val upcoming = programme(2, now + 1_000, now + 2_000)
        val live = programme(1)
        val result = LocalSearch(FakeDao(emptyList(), listOf(past, unavailable, upcoming, live)))
            .search(1, "wiadomosci", now)
        assertEquals(listOf(1L, 2L, 3L), result.programmes.map { it.programmeId })
    }

    @Test fun paginationKeepsChannelsSharingAProgrammeAndStartTime() = runBlocking {
        val programmes = (1L..250).map { id -> programme(channel = channel(id),
            title = if (id <= 200) "other" else "Wiadomości") }
        val result = LocalSearch(FakeDao(emptyList(), programmes)).search(1, "wiadomosci", now)
        assertEquals((201L..250).toList(), result.programmes.map { it.channel.id })
    }

    @Test fun databaseFailureAndCancellationPropagateToTheUiCaller() = runBlocking {
        for (failure in listOf(IOException("fixture"), CancellationException("cancelled"))) {
            val dao = object : SearchDao {
                override suspend fun channels(sourceId: Long, afterId: Long, limit: Int): List<ChannelEntity> = throw failure
                override suspend fun programmes(sourceId: Long, now: Long, until: Long, past: Boolean, afterStart: Long,
                    afterChannel: Long, afterProgramme: Long, limit: Int): List<SearchProgramme> = error("not reached")
            }
            try {
                LocalSearch(dao).search(1, "test", now)
                fail("must propagate")
            } catch (actual: Exception) {
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
            }
        }
    }

    private class FakeDao(val channels: List<ChannelEntity>, val programmes: List<SearchProgramme>) : SearchDao {
        var calls = 0
        val threads = mutableListOf<String>()
        private fun record() { calls++; threads += Thread.currentThread().name }
        override suspend fun channels(sourceId: Long, afterId: Long, limit: Int): List<ChannelEntity> {
            record()
            return channels.filter { it.sourceId == sourceId && it.id > afterId }.sortedBy { it.id }.take(limit)
        }
        override suspend fun programmes(sourceId: Long, now: Long, until: Long, past: Boolean, afterStart: Long,
            afterChannel: Long, afterProgramme: Long, limit: Int): List<SearchProgramme> {
            record()
            return programmes.filter { row ->
                row.channel.sourceId == sourceId && row.stop > row.start &&
                    (if (past) row.stop <= now else row.stop > now && row.start < until) &&
                    ((if (past) row.start < afterStart else row.start > afterStart) ||
                        (row.start == afterStart && (row.channel.id > afterChannel ||
                            (row.channel.id == afterChannel && row.programmeId > afterProgramme))))
            }.sortedWith(compareBy<SearchProgramme> { if (past) -it.start else it.start }
                .thenBy { it.channel.id }.thenBy { it.programmeId }).take(limit)
        }
    }
}
