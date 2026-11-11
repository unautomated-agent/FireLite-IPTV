package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.*
import dev.firetvlite.iptv.R
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class FilmsTest {
    private val now = Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
    private fun film(title: String = "Był sobie chłopiec", year: Int? = 2002) = ProgrammeEntity(
        sourceId = 1, generation = "g", epgKey = "c", start = now, stop = now + 120 * 60_000,
        title = title, description = null, category = "dramat", year = year, originalTitle = "About a Boy")

    @Test fun realFilmShapesAndMalformedGenresAreCandidates() {
        assertTrue(Films.candidate(film()))
        assertTrue(Films.candidate(film("Liberator 2", 1995).copy(categories = "sensacja\nFILM FABULARNY KINOWY")))
        assertTrue(Films.candidate(film().copy(category = "FILM ANIMOWANY\",]\"name\":\"")))
        assertTrue(Films.candidate(film().copy(category = "animowany film")))
        assertTrue(Films.candidate(film().copy(category = "SCI-FI")))
    }
    @Test fun rejectsEpisodesIncludingMislabelledRawhideAndSeriesMarkers() {
        assertFalse(Films.candidate(film("Rawhide", 1962).copy(category = "FILM FABULARNY TELEWIZYJNY", episodeNum = "S4 E7")))
        assertFalse(Films.candidate(film().copy(episodeNum = "")))
        for (marker in listOf("serial", "series", "sezon 2", "odc. 3", "S4 E7", "(2/7)")) {
            assertFalse(marker, Films.candidate(film("Film $marker")))
        }
        assertFalse(Films.candidate(film().copy(categories = "dramat\nSERIAL OBYCZAJOWY")))
        assertFalse(Films.candidate(film().copy(originalTitle = "Rawhide S4 E7")))
    }
    @Test fun genericAndEnglishFilmCategoriesStillRequireFilmMetadata() {
        for (category in listOf("Movie", "Film", "MOVIES", "films", "Feature Film", "TV Movie", "Animated Film", "Drama", "Comedy", "Action", "Crime", "Adventure", "War", "Romance")) {
            assertTrue(category, Films.candidate(film().copy(category = category)))
            assertFalse(category, Films.candidate(film().copy(category = category, episodeNum = "S1 E1")))
            assertFalse(category, Films.candidate(film("TV series").copy(category = category)))
            assertFalse(category, Films.candidate(film(year = null).copy(category = category)))
        }
        assertFalse(Films.candidate(film().copy(category = "Movie news")))
    }
    @Test fun requiresYearDurationAndFilmCategory() {
        assertFalse(Films.candidate(film(year = null)))
        assertFalse(Films.candidate(film(year = 999)))
        assertFalse(Films.candidate(film().copy(category = "wiadomości")))
        assertFalse(Films.candidate(film().copy(category = null)))
        assertFalse(Films.candidate(film().copy(stop = now + 59 * 60_000)))
        assertTrue(Films.candidate(film().copy(stop = now + 60 * 60_000)))
        assertTrue(Films.candidate(film().copy(stop = now + 240 * 60_000)))
        assertFalse(Films.candidate(film().copy(stop = now + 241 * 60_000)))
    }
    @Test fun enrichmentIncludesAiringAndFutureFilmsButNotExpiredOrOutsideWindow() {
        val airing = film().copy(start = now - 60 * 60_000, stop = now + 60 * 60_000)
        val future = film("Future").copy(originalTitle = "Future", start = now + Films.DAY, stop = now + Films.DAY + 120 * 60_000)
        val ended = airing.copy(stop = now)
        val outside = future.copy(start = now + 2 * Films.DAY, stop = now + 2 * Films.DAY + 120 * 60_000)
        assertEquals(listOf(airing, future), ratingCandidates(listOf(airing, future, ended, outside, airing), now))
        assertTrue(ratingCandidates(emptyList(), now).isEmpty())
        assertTrue(ratingCandidates(listOf(airing.copy(episodeNum = "S1 E1")), now).isEmpty())
    }
    @Test fun selectsOriginalThenPolishAndAdjacentYearsWithoutDuplicateQueries() {
        assertEquals(listOf("About a Boy" to 2002, "Był sobie chłopiec" to 2002,
            "About a Boy" to 2001, "Był sobie chłopiec" to 2001,
            "About a Boy" to 2003, "Był sobie chłopiec" to 2003), Films.queries(film()))
        assertEquals(3, Films.queries(film("About a Boy")).size)
        assertEquals("about a boy:2002", Films.key(film()))
        assertEquals(Films.key(film()), Films.key(film().copy(originalTitle = " ABOUT A BOY! ")))
        assertEquals("Był sobie chłopiec", Films.queries(film().copy(originalTitle = null)).first().first)
    }
    @Test fun onlyAcceptsMovieAndNearYear() {
        val movie = OmdbMovie("movie", 2002, 7.1, 123, "tt123")
        assertTrue(movie.matches(2001))
        assertTrue(movie.matches(2003))
        assertFalse(movie.matches(2004))
        assertFalse(movie.copy(type = "series").matches(2002))
        assertFalse(movie.copy(id = "unknown").matches(2002))
    }
    @Test fun positiveAndNegativeTtlsAndClockRollback() {
        val positive = FilmRatingEntity("k", 7.1, 123, "tt123", now)
        val negative = positive.copy(imdbId = null, imdbRating = null)
        assertTrue(Films.fresh(positive, now + 30 * Films.DAY - 1))
        assertFalse(Films.fresh(positive, now + 30 * Films.DAY))
        assertTrue(Films.fresh(negative, now + 7 * Films.DAY - 1))
        assertFalse(Films.fresh(negative, now + 7 * Films.DAY))
        assertFalse(Films.fresh(positive, now - 1))
    }
    @Test fun newRatingsAreVisibleImmediatelyEvenBetweenMinuteTicks() = runBlocking {
        val row = FilmRatingEntity("film", 7.1, 1, "tt123", now + 1000)
        val ratings = observeFilmRatings(flowOf(listOf(row)), flowOf("fixture"), flowOf(now)) { now + 1001 }.first()
        assertEquals(row, ratings["film"])
        assertTrue(observeFilmRatings(flowOf(listOf(row)), flowOf(""), flowOf(now)) { now + 1001 }.first().isEmpty())
    }

    private class Store : FilmStore {
        val rows = mutableMapOf<String, FilmRatingEntity>()
        val used = mutableMapOf<Long, Int>()
        override suspend fun cached(key: String) = rows[key]
        override suspend fun save(rating: FilmRatingEntity) { rows[rating.cacheKey] = rating }
        override suspend fun reserve(day: Long): Boolean {
            val count = used[day] ?: 0
            if (count >= 400) return false
            used[day] = count + 1
            return true
        }
    }
    @Test fun fakeClientExercisesFallbackCachesAndNoKey() = runBlocking {
        val store = Store()
        val calls = mutableListOf<Pair<String, Int>>()
        var waits = 0
        val client = OmdbClient { _, title, year ->
            calls.add(title to year)
            if (title == "Był sobie chłopiec" && year == 2001)
                OmdbResult.Found(OmdbMovie("movie", 2001, 7.1, 125, "tt123")) else OmdbResult.Missing
        }
        val lookup = FilmLookup(client, store, { now }) { waits++ }
        assertNull(lookup.lookup(film(), "", now))
        val result = lookup.lookup(film(), "fixture-key", now)!!
        assertEquals(4, calls.size)
        assertEquals(calls.size, waits)
        assertEquals(7.1, result.imdbRating!!, 0.0)
        assertEquals(result, FilmLookup(client, store, { now }) {}.lookup(film(), "fixture-key", now + 1000))
        assertEquals(4, calls.size)
    }
    @Test fun failuresDoNotPoisonCacheAndMissingResultsAreNegativeCached() = runBlocking {
        val store = Store()
        val failed = FilmLookup(OmdbClient { _, _, _ -> OmdbResult.Failed }, store, { now }) {}
        assertNull(failed.lookup(film(), "fixture", now))
        assertTrue(store.rows.isEmpty())
        var calls = 0
        val missing = FilmLookup(OmdbClient { _, _, _ -> calls++; OmdbResult.Missing }, store, { now }) {}
        assertNull(missing.lookup(film(), "fixture", now)!!.imdbRating)
        assertEquals(6, calls)
        missing.lookup(film(), "fixture", now + Films.DAY)
        assertEquals(6, calls)
    }
    @Test fun budgetSurvivesLookupRecreationAndCountsEveryFallbackRequest() = runBlocking {
        val store = Store()
        val day = Films.day(now)
        store.used[day] = 399
        var requests = 0
        val client = OmdbClient { _, _, _ -> requests++; OmdbResult.Missing }
        assertNull(FilmLookup(client, store, { now }) {}.lookup(film(), "fixture", now))
        assertEquals(400, store.used[day])
        assertNull(FilmLookup(client, store, { now }) {}.lookup(film(), "fixture", now))
        assertEquals(1, requests)
        FilmLookup(client, store, { now + Films.DAY }) {}.lookup(film(), "fixture", now + Films.DAY)
        assertEquals(7, requests)
    }
    @Test fun midnightFallbackUsesTheRequestDayAndCompletionTimestamp() = runBlocking {
        var time = Instant.parse("2026-03-28T22:59:59.500Z").toEpochMilli()
        val started = time
        val store = Store()
        var requests = 0
        val client = OmdbClient { _, _, _ ->
            if (++requests == 1) { time += 500; OmdbResult.Missing }
            else OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 1, "tt123"))
        }
        val result = FilmLookup(client, store, { time }) { time += 250 }.lookup(film(), "fixture", started)!!
        assertEquals(1, store.used[Films.day(started)])
        assertEquals(1, store.used[Films.day(started) + 1])
        assertEquals(time, result.fetchedAt)
    }
    @Test fun delayCrossingMidnightDoesNotChargeAnExhaustedPreviousDay() = runBlocking {
        var time = Instant.parse("2026-03-28T22:59:59.900Z").toEpochMilli()
        val started = time
        val store = Store()
        store.used[Films.day(started)] = 400
        val client = OmdbClient { _, _, _ -> OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 1, "tt123")) }
        assertNotNull(FilmLookup(client, store, { time }) { time += 250 }.lookup(film(), "fixture", started))
        assertEquals(400, store.used[Films.day(started)])
        assertEquals(1, store.used[Films.day(started) + 1])
    }
    @Test fun parsesRatingsVotesAndNonFatalResponses() {
        val result = OmdbHttpClient.decode("""{"Response":"True","Type":"movie","Year":"2002","imdbRating":"7.1","imdbVotes":"123,456","imdbID":"tt123"}""") as OmdbResult.Found
        assertEquals(123456L, result.movie.votes)
        assertEquals(OmdbResult.Missing, OmdbHttpClient.decode("""{"Response":"False","Error":"Movie not found!"}"""))
        assertEquals(OmdbResult.Failed, OmdbHttpClient.decode("""{"Response":"False","Error":"Invalid API key!"}"""))
        assertEquals(OmdbResult.Failed, OmdbHttpClient.decode("garbage"))
        assertEquals(R.string.omdb_key_set, omdbKeyStatus(true))
        assertEquals(R.string.omdb_key_missing, omdbKeyStatus(false))
    }
    private fun channel() = ChannelEntity(id = 1, sourceId = 1, position = 0, name = "Kino", groupName = "Filmy",
        streamUrl = "unused", logoUrl = null, tvgId = null, catchupType = null, catchupSource = null, catchupDays = 0)
    @Test fun rankingsUseHalfOpenWindowsIncludeAiringAndSortRatingThenVotes() {
        fun showing(start: Long, score: Double?, votes: Long = 100) = FilmShowing(channel(), film().copy(start = start,
            stop = start + 120 * 60_000), score?.let { FilmRatingEntity("k", it, votes, "tt123", now) })
        val airing = showing(now - 60_000, 8.0)
        val lowVotes = showing(now + 1000, 8.0, 5)
        val unrated = showing(now + 2000, null)
        val tomorrow = showing(now + Films.DAY, 9.0)
        val outside = showing(now + 2 * Films.DAY, 10.0)
        val ended = showing(now - 120 * 60_000, 10.0)
        val films = listOf(unrated, lowVotes, outside, ended, tomorrow, airing)
        assertEquals(listOf(airing, lowVotes, unrated), rankedFilms(films, now, 0))
        assertEquals(listOf(tomorrow), rankedFilms(films, now, 1))
        assertTrue(rankedFilms(emptyList(), now, 0).isEmpty())
        assertEquals("niedz. 29.03 00:30", Films.time(now))
        assertEquals("pon. 30.03 01:30", Films.time(now + Films.DAY))
        assertEquals(Films.day(now) + 1, Films.day(now + Films.DAY))
    }
}
