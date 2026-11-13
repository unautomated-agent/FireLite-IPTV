package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.film.*
import org.junit.Assert.*
import org.junit.Test

class FilmProgressTest {
    private val now = 1_800_000_000_000L
    private val channel = ChannelEntity(id = 1, sourceId = 1, position = 0, name = "Test", groupName = "",
        streamUrl = "unused", logoUrl = null, tvgId = "c", catchupType = "shift", catchupSource = null, catchupDays = 7)
    private fun showing(title: String, start: Long, rating: Boolean? = null) : FilmShowing {
        val p = ProgrammeEntity(sourceId = 1, generation = "g", epgKey = "c", start = start,
            stop = start + 90 * 60_000, title = title, description = null, category = "dramat", year = 2001)
        return FilmShowing(channel, p, rating?.let { FilmRatingEntity(Films.key(p), if (it) 7.0 else null,
            0, if (it) "tt123" else null, now) })
    }
    @Test fun countsUniqueCandidatesPerTabIncludingNegativeCacheAndUnratedImdbMatches() {
        val hit = showing("Hit", now - 1000, true)
        val missing = showing("Miss", now - 1000, false)
        val unknown = showing("Unknown", now - 1000)
        val unrated = showing("Unrated", now - 1000, false).let { it.copy(rating = it.rating!!.copy(imdbId = "tt456")) }
        val rows = listOf(hit, hit.copy(channel = channel.copy(id = 2)), missing, unknown, unrated,
            showing("Past", now - Films.DAY, false), showing("Tomorrow", now + 1000), showing("Later", now + Films.DAY))
        val progress = filmProgress(rows, now)
        assertEquals(FilmProgress(4, 3, 1, 1), progress[Films.LIVE_TAB])
        assertEquals(FilmProgress(1, 1, 0, 1), progress[Films.ARCHIVE_TAB])
        assertEquals(1, progress[Films.UPCOMING_TAB].total)
        assertEquals(1, progress[Films.FUTURE_TAB].total)
        assertEquals(0.75f, progress[Films.LIVE_TAB].fraction)
    }
    @Test fun emptyAndInvalidCandidatesHaveZeroProgress() {
        assertTrue(filmProgress(emptyList(), now).all { it.fraction == 0f && it.total == 0 })
        val invalid = showing("Episode", now).let { it.copy(programme = it.programme.copy(episodeNum = "S1 E1")) }
        assertEquals(0, filmProgress(listOf(invalid), now).sumOf { it.total })
    }
}
