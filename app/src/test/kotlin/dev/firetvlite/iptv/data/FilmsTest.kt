package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.*
import dev.firetvlite.iptv.R
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.collect
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
        assertFalse(Films.candidate(film().copy(stop = now + 60 * 60_000)))
        assertFalse(Films.candidate(film().copy(stop = now + 80 * 60_000 - 1)))
        assertTrue(Films.candidate(film().copy(stop = now + 80 * 60_000)))
        assertTrue(Films.candidate(film().copy(stop = now + 240 * 60_000)))
        assertTrue(Films.candidate(film().copy(stop = now + 241 * 60_000)))
        assertFalse(Films.candidate(film().copy(stop = now + 300 * 60_000, category = "wiadomości")))
        assertFalse(Films.candidate(film().copy(stop = now + 300 * 60_000, episodeNum = "S1 E1")))
        assertFalse(Films.candidate(film().copy(stop = now)))
        assertFalse(Films.candidate(film().copy(stop = now - 1)))
    }
    @Test fun mixedUnratedAndMissingRatingsSortLongVotesAcrossAllTabs() {
        for (tab in 0 until Films.TAB_COUNT) {
            val start = when (tab) {
                Films.LIVE_TAB -> now - 100
                Films.UPCOMING_TAB -> now + 100
                Films.FUTURE_TAB -> now + Films.DAY
                else -> now - Films.DAY
            }
            val missing = FilmShowing(channel().copy(catchupType = "shift", catchupDays = 2),
                film().copy(start = start, stop = start + 7_200_000), null)
            val unrated = missing.copy(programme = missing.programme.copy(start = start + 1),
                rating = FilmRatingEntity("unrated", null, 123L, "tt123", now))
            val noVotes = missing.copy(programme = missing.programme.copy(start = start + 2),
                rating = FilmRatingEntity("no-votes", null, 0L, "tt456", now))
            val rated = missing.copy(programme = missing.programme.copy(start = start + 3),
                rating = FilmRatingEntity("rated", 8.0, 5L, "tt789", now))
            val expected = listOf(rated)
            for (input in listOf(listOf(rated, noVotes, unrated, missing), listOf(missing, unrated, noVotes, rated))) {
                assertEquals(expected, rankedFilms(input, now, tab))
            }
        }
    }
    @Test fun shortFilmsNeverReachRankingsOrSpendLookupRequests() = runBlocking {
        val short = film("Short").copy(stop = now + 79 * 60_000)
        val full = film("Full").copy(originalTitle = null, stop = now + 80 * 60_000)
        val showings = listOf(short, full).map { FilmShowing(channel(), it, FilmRatingEntity("k", 8.0, 10L, "tt123", now)) }
        assertEquals(listOf(full), ratingCandidates(showings, now))
        assertEquals(listOf(full), rankedFilms(showings, now, 0).map { it.programme })
        var requests = 0
        val lookup = FilmLookup(OmdbClient { _, _, _ -> requests++; OmdbResult.Missing }, object : FilmStore {
            override suspend fun cached(key: String): FilmRatingEntity? = null
            override suspend fun save(rating: FilmRatingEntity) = Unit
            override suspend fun reserve(day: Long) = true
        }, wait = {})
        assertNull(lookup.lookup(short, "fixture", now))
        lookup.lookup(full, "fixture", now)
        assertEquals(1, requests)
    }
    @Test fun enrichmentIncludesAiringAndFutureFilmsButNotExpiredOrOutsideWindow() {
        val airing = film().copy(start = now - 60 * 60_000, stop = now + 60 * 60_000)
        val future = film("Future").copy(originalTitle = "Future", start = now + Films.DAY, stop = now + Films.DAY + 120 * 60_000)
        val ended = airing.copy(stop = now)
        val outside = future.copy(start = now + 2 * Films.DAY, stop = now + 2 * Films.DAY + 120 * 60_000)
        val showings = listOf(airing, future, ended, outside, airing).map {
            FilmShowing(channel(), it, null)
        }
        assertEquals(listOf(airing), ratingCandidates(showings, now))
        assertEquals(listOf(future), ratingCandidates(showings, now, futureWindow = true))
        assertTrue(ratingCandidates(emptyList(), now).isEmpty())
        assertTrue(ratingCandidates(listOf(FilmShowing(channel(), airing.copy(episodeNum = "S1 E1"), null)), now).isEmpty())
    }
    @Test fun selectsAtMostTwoExactYearTitlesWithoutDuplicateQueries() {
        assertEquals(listOf("About a Boy" to 2002, "Był sobie chłopiec" to 2002), Films.queries(film()))
        assertEquals(listOf("ABOUT A BOY!" to 2002), Films.queries(film("ABOUT A BOY!")))
        assertEquals("about a boy:2002", Films.key(film()))
        assertEquals(Films.key(film()), Films.key(film().copy(originalTitle = " ABOUT A BOY! ")))
        assertEquals("Był sobie chłopiec", Films.queries(film().copy(originalTitle = null)).first().first)
    }
    @Test fun localOnlyTitlesUseOneRequestAndOriginalTitlesUseAtMostTwo() {
        for (original in listOf(null, "", "  ")) {
            assertEquals(listOf("Był sobie chłopiec" to 2002), Films.queries(film().copy(originalTitle = original)))
        }
        assertTrue(Films.queries(film(year = null)).isEmpty())
        assertTrue(Films.queries(film("").copy(originalTitle = null)).isEmpty())
        assertEquals(2, Films.queries(film()).size)
    }

    @Test fun backgroundPrioritizesUpcomingThenArchiveAndFutureRequiresATabRequest() {
        val archiveChannel = channel().copy(catchupType = "shift", catchupDays = 7)
        fun showing(title: String, start: Long) = FilmShowing(archiveChannel,
            film(title).copy(originalTitle = title, start = start, stop = start + 7_200_000), null)
        val airing = showing("Airing", now - 60_000)
        val soon = showing("Soon", now + 1000)
        val tomorrow = showing("Tomorrow", now + Films.DAY)
        val recent = showing("Recent", now - Films.DAY)
        val older = showing("Older", now - 2 * Films.DAY)
        val expired = showing("Expired", now - 8 * Films.DAY)
        val outside = showing("Outside", now + 2 * Films.DAY)
        val inputs = listOf(tomorrow, older, expired, soon, outside, recent, airing)
        assertEquals(listOf(airing, soon, recent, older).map { it.programme }, ratingCandidates(inputs, now))
        assertEquals(listOf(tomorrow.programme), ratingCandidates(inputs, now, futureWindow = true))
        val rated = inputs.map { it.copy(rating = FilmRatingEntity("k", 8.0, 10L, "tt123", now)) }
        val visible = (0 until Films.TAB_COUNT).flatMap { rankedFilms(rated, now, it) }.map { Films.key(it.programme) }.toSet()
        assertEquals(visible, (ratingCandidates(inputs, now) + ratingCandidates(inputs, now, futureWindow = true))
            .map(Films::key).toSet())
        val duplicateTomorrow = soon.copy(programme = soon.programme.copy(start = now + Films.DAY, stop = now + Films.DAY + 7_200_000))
        assertEquals(listOf(soon.programme), ratingCandidates(listOf(duplicateTomorrow, soon), now))
        assertTrue(ratingCandidates(listOf(recent.copy(channel = channel()), outside, expired), now).isEmpty())
    }

    @Test fun exactMovieHitStopsAllRemainingFallbacks() = runBlocking {
        val store = Store()
        var requests = 0
        val lookup = FilmLookup(OmdbClient { _, _, _ ->
            requests++
            OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 100, "tt123"))
        }, store, { now }) {}
        assertNotNull(lookup.lookup(film(), "fixture", now))
        assertEquals(1, requests)
    }

    @Test fun requestedFutureComesBeforeBackgroundWithoutDuplicatingFilms() {
        val archiveChannel = channel().copy(catchupType = "shift", catchupDays = 7)
        fun showing(title: String, start: Long) = FilmShowing(archiveChannel,
            film(title).copy(originalTitle = title, start = start, stop = start + 7_200_000), null)
        val future = showing("Future", now + Films.DAY)
        val duplicateToday = showing("Future", now)
        val today = showing("Today", now)
        val archive = showing("Archive", now - Films.DAY)
        val inputs = listOf(archive, today, duplicateToday, future)
        assertEquals(listOf(future.programme, today.programme, archive.programme),
            enrichmentCandidates(inputs, now, futureRequested = true))
        assertEquals(ratingCandidates(inputs, now), enrichmentCandidates(inputs, now, futureRequested = false))
        assertTrue(enrichmentCandidates(emptyList(), now, futureRequested = true).isEmpty())
    }

    @Test fun retryingFuturePassStillEnrichesTodayAndArchive() = runBlocking {
        val archiveChannel = channel().copy(catchupType = "shift", catchupDays = 7)
        fun showing(title: String, start: Long) = FilmShowing(archiveChannel,
            film(title).copy(originalTitle = title, start = start, stop = start + 7_200_000), null)
        val future = showing("Future", now + Films.DAY)
        val today = showing("Today", now)
        val archive = showing("Archive", now - Films.DAY)
        val candidates = enrichmentCandidates(listOf(archive, future, today), now, futureRequested = true)
        val store = Store()
        val calls = mutableListOf<String>()
        val lookup = FilmLookup(OmdbClient { _, title, _ ->
            calls.add(title)
            if (title == "Future") OmdbResult.Failed("HTTP_503")
            else OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 100, "tt123"))
        }, store, { now }) {}
        repeat(2) { assertTrue(enrichFilms(candidates, "fixture", lookup, { true }) { now }) }
        assertEquals(listOf("Future", "Today", "Archive", "Future"), calls)
        assertNotNull(store.rows[Films.key(today.programme)])
        assertNotNull(store.rows[Films.key(archive.programme)])
    }

    @Test fun tabRequestsOnlyLookupUncachedFutureFilmsAndShareTheDailyBudget() = runBlocking {
        val store = Store()
        store.used[Films.day(now)] = Films.DAILY_BUDGET - 1
        val cached = film().copy(start = now + Films.DAY, stop = now + Films.DAY + 7_200_000)
        val uncached = cached.copy(title = "Different", originalTitle = null)
        store.rows[Films.key(cached)] = FilmRatingEntity(Films.key(cached), 8.0, 100, "tt123", now)
        val showings = listOf(cached, uncached).map { FilmShowing(channel(), it, null) }
        assertTrue(ratingCandidates(showings, now).isEmpty())
        val queries = mutableListOf<String>()
        val lookup = FilmLookup(OmdbClient { _, title, _ -> queries.add(title); OmdbResult.Missing }, store, { now }) {}
        assertFalse(enrichFilms(ratingCandidates(showings, now, futureWindow = true), "fixture", lookup, { true }) { now })
        assertEquals(listOf("Different"), queries)
        assertEquals(Films.DAILY_BUDGET, store.used[Films.day(now)])
        assertNull(lookup.lookup(uncached.copy(title = "Another"), "fixture", now))
        assertEquals("LOCAL_BUDGET", lookup.lastError)
        assertEquals(1, queries.size)
        assertEquals(listOf(cached, uncached), ratingCandidates(showings, now + Films.DAY))
        assertTrue(ratingCandidates(emptyList(), now, futureWindow = true).isEmpty())
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
        assertTrue(Films.fresh(negative, now + 30 * Films.DAY - 1))
        assertFalse(Films.fresh(negative, now + 30 * Films.DAY))
        assertFalse(Films.fresh(negative, now - 1))
        assertFalse(Films.fresh(positive, now - 1))
    }
    @Test fun newRatingsAreVisibleImmediatelyEvenBetweenMinuteTicks() = runBlocking {
        val row = FilmRatingEntity("film", 7.1, 1, "tt123", now + 1000)
        val ratings = observeFilmRatings(flowOf(listOf(row)), flowOf("fixture"), flowOf(now)) { now + 1001 }.first()
        assertEquals(row, ratings["film"])
        assertTrue(observeFilmRatings(flowOf(listOf(row)), flowOf(""), flowOf(now)) { now + 1001 }.first().isEmpty())
    }

    @Test fun variantMissRecordsStayPrivateWithoutHidingAFilmNamedVariant() = runBlocking {
        val row = FilmRatingEntity("variant:2002", 7.1, 1, "tt123", now)
        val progress = FilmRatingEntity(Films.variantKey(row.cacheKey, "Variant", 2002), null, 0, null, now)
        val ratings = observeFilmRatings(flowOf(listOf(row, progress)), flowOf("fixture"), flowOf(now)) { now }.first()
        assertEquals(mapOf(row.cacheKey to row), ratings)
    }

    @Test fun subscribedRatingsUpdateWhenRoomEmitsWithoutAnotherClockTick() = runBlocking {
        val programme = film()
        val row = FilmRatingEntity(Films.key(programme), 7.1, 125, "tt0276751", now + 1000)
        val rows = MutableStateFlow<List<FilmRatingEntity>>(emptyList())
        val initial = CompletableDeferred<Unit>()
        val received = mutableListOf<Map<String, FilmRatingEntity>>()
        withTimeout(5000) {
            val observer = launch {
                observeFilmRatings(rows, flowOf("fixture"), flowOf(now)) { now + 1001 }
                    .take(2).collect {
                        received.add(it)
                        initial.complete(Unit)
                    }
            }
            initial.await()
            rows.value = listOf(row)
            observer.join()
        }
        assertTrue(received.first().isEmpty())
        assertEquals(row, received.last()[Films.key(programme)])
        val showing = FilmShowing(channel(), programme, received.last()[Films.key(programme)])
        assertEquals(row, rankedFilms(listOf(showing), now, 0).single().rating)
    }

    private class Store : FilmStore {
        val rows = mutableMapOf<String, FilmRatingEntity>()
        val used = mutableMapOf<Long, Int>()
        override suspend fun cached(key: String) = rows[key]
        override suspend fun save(rating: FilmRatingEntity) { rows[rating.cacheKey] = rating }
        override suspend fun reserve(day: Long): Boolean {
            val count = used[day] ?: 0
            if (count >= Films.DAILY_BUDGET) return false
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
            if (title == "Był sobie chłopiec" && year == 2002)
                OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 125, "tt123")) else OmdbResult.Missing
        }
        val lookup = FilmLookup(client, store, { now }) { waits++ }
        assertNull(lookup.lookup(film(), "", now))
        val result = lookup.lookup(film(), "fixture-key", now)!!
        assertEquals(listOf("About a Boy" to 2002, "Był sobie chłopiec" to 2002), calls)
        assertEquals(calls.size, waits)
        assertEquals(7.1, result.imdbRating!!, 0.0)
        assertEquals(result, FilmLookup(client, store, { now }) {}.lookup(film(), "fixture-key", now + 1000))
        assertEquals(2, calls.size)
    }
    @Test fun failuresDoNotPoisonCacheAndMissingResultsAreNegativeCached() = runBlocking {
        val store = Store()
        val failed = FilmLookup(OmdbClient { _, _, _ -> OmdbResult.Failed() }, store, { now }) {}
        assertNull(failed.lookup(film(), "fixture", now))
        assertTrue(store.rows.isEmpty())
        var calls = 0
        val missing = FilmLookup(OmdbClient { _, _, _ -> calls++; OmdbResult.Missing }, store, { now }) {}
        assertNull(missing.lookup(film(), "fixture", now)!!.imdbRating)
        assertEquals(2, calls)
        missing.lookup(film(), "fixture", now + Films.DAY)
        assertEquals(2, calls)
    }
    @Test fun budgetSurvivesLookupRecreationAndCountsEveryFallbackRequest() = runBlocking {
        val store = Store()
        val day = Films.day(now)
        store.used[day] = Films.DAILY_BUDGET - 1
        var requests = 0
        val client = OmdbClient { _, _, _ -> requests++; OmdbResult.Missing }
        assertNull(FilmLookup(client, store, { now }) {}.lookup(film(), "fixture", now))
        assertEquals(Films.DAILY_BUDGET, store.used[day])
        assertNull(FilmLookup(client, store, { now }) {}.lookup(film(), "fixture", now))
        assertEquals(1, requests)
        FilmLookup(client, store, { now + Films.DAY }) {}.lookup(film(), "fixture", now + Films.DAY)
        assertEquals(2, requests)
    }

    @Test fun theRaisedBudgetKeepsASafetyMarginAndExistingFourHundredRequestsDoNotBlockLookup() = runBlocking {
        assertEquals(950, Films.DAILY_BUDGET)
        assertEquals(50, 1000 - Films.DAILY_BUDGET)
        val store = Store()
        store.used[Films.day(now)] = 400
        var requests = 0
        val lookup = FilmLookup(OmdbClient { _, _, _ ->
            requests++
            OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 100, "tt123"))
        }, store, { now }) {}
        assertNotNull(lookup.lookup(film(), "fixture", now))
        assertEquals(1, requests)
        assertEquals(401, store.used[Films.day(now)])
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
        store.used[Films.day(started)] = Films.DAILY_BUDGET
        val client = OmdbClient { _, _, _ -> OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 1, "tt123")) }
        assertNotNull(FilmLookup(client, store, { time }) { time += 250 }.lookup(film(), "fixture", started))
        assertEquals(Films.DAILY_BUDGET, store.used[Films.day(started)])
        assertEquals(1, store.used[Films.day(started) + 1])
    }
    @Test fun parsesRatingsVotesAndNonFatalResponses() {
        val result = OmdbHttpClient.decode("""{"Response":"True","Type":"movie","Year":"2002","imdbRating":"7.1","imdbVotes":"123,456","imdbID":"tt123"}""") as OmdbResult.Found
        assertEquals(123456L, result.movie.votes)
        assertEquals(OmdbResult.Missing, OmdbHttpClient.decode("""{"Response":"False","Error":"Movie not found!"}"""))
        assertEquals(OmdbResult.Failed("AUTH"), OmdbHttpClient.decode("""{"Response":"False","Error":"Invalid API key!"}"""))
        assertEquals(OmdbResult.Failed("BAD_RESPONSE"), OmdbHttpClient.decode("garbage"))
        assertEquals(R.string.omdb_key_set, omdbKeyStatus(true))
        assertEquals(R.string.omdb_key_missing, omdbKeyStatus(false))
    }
    private fun channel() = ChannelEntity(id = 1, sourceId = 1, position = 0, name = "Kino", groupName = "Filmy",
        streamUrl = "unused", logoUrl = null, tvgId = null, catchupType = null, catchupSource = null, catchupDays = 0)

    @Test fun archiveEligibilityUsesStartWindowEndedStatusAndSupportedCatchup() {
        val channel = channel().copy(catchupType = "shift", catchupDays = 2)
        val boundary = film().copy(start = now - 2 * Films.DAY, stop = now - 2 * Films.DAY + 120 * 60_000)
        assertTrue(Films.archived(channel, boundary, now))
        assertEquals(now, Films.archiveUntil(channel, boundary))
        assertFalse(Films.archived(channel, boundary.copy(start = boundary.start - 1), now))
        assertTrue(Films.archived(channel, boundary.copy(stop = now), now))
        assertFalse(Films.archived(channel, boundary.copy(stop = now + 1), now))
        assertFalse(Films.archived(channel, boundary.copy(stop = boundary.start), now))
        assertFalse(Films.archived(channel.copy(catchupDays = 0), boundary, now))
        assertFalse(Films.archived(channel.copy(catchupType = "unsupported"), boundary, now))
        assertFalse(Films.archived(channel.copy(catchupType = "default", catchupSource = null), boundary, now))
        assertEquals(now - 2 * Films.DAY, Films.archiveWindowStart(listOf(channel,
            channel.copy(catchupDays = 20, catchupType = "unsupported")), now))
        assertEquals(now, Films.archiveWindowStart(emptyList(), now))
    }

    @Test fun archiveRankingsSortRatingsThenVotesAndExcludeExpiredUnsupportedAndAiring() {
        val channel = channel().copy(catchupType = "shift", catchupDays = 2)
        fun showing(score: Double?, votes: Long) = FilmShowing(channel,
            film().copy(start = now - Films.DAY - votes * 60_000, stop = now - Films.DAY - votes * 60_000 + 120 * 60_000),
            score?.let { FilmRatingEntity("k", it, votes, "tt123", now) })
        val best = showing(9.0, 10)
        val votes = showing(8.0, 100)
        val fewerVotes = showing(8.0, 1)
        val unrated = showing(null, 0)
        val expired = best.copy(programme = best.programme.copy(start = now - 3 * Films.DAY,
            stop = now - 3 * Films.DAY + 120 * 60_000))
        val unsupported = best.copy(channel = channel.copy(catchupType = "unsupported"))
        val airing = best.copy(programme = film().copy(start = now - 60_000))
        val duplicate = best.copy(programme = best.programme.copy(stop = best.programme.stop + 1_000))
        val films = listOf(unrated, fewerVotes, votes, expired, unsupported, airing, best, duplicate)
        assertEquals(listOf(best, votes, fewerVotes), rankedFilms(films, now, Films.ARCHIVE_TAB))
        assertTrue(rankedFilms(emptyList(), now, Films.ARCHIVE_TAB).isEmpty())
        assertTrue(rankedFilms(films, now, Films.TAB_COUNT).isEmpty())
    }

    @Test fun enrichmentIncludesArchiveOnlyOnSupportedChannelsAndPrioritizesUpcomingCopies() {
        val channel = channel().copy(catchupType = "shift", catchupDays = 2)
        val past = film().copy(start = now - Films.DAY, stop = now - Films.DAY + 120 * 60_000)
        val archive = FilmShowing(channel, past, null)
        val future = FilmShowing(channel, film(), null)
        assertEquals(listOf(past), ratingCandidates(listOf(archive), now))
        assertEquals(listOf(future.programme), ratingCandidates(listOf(archive, future), now))
        assertTrue(ratingCandidates(listOf(archive.copy(channel = channel.copy(catchupDays = 0)),
            archive.copy(programme = past.copy(start = now - 3 * Films.DAY, stop = now - 3 * Films.DAY + 120 * 60_000)),
            archive.copy(programme = past.copy(episodeNum = "S1 E1"))), now).isEmpty())
    }

    @Test fun archiveLookupsShareTheSameCacheAndDailyBudgetAsUpcomingFilms(): Unit = runBlocking {
        val store = Store()
        store.used[Films.day(now)] = Films.DAILY_BUDGET - 1
        var requests = 0
        val lookup = FilmLookup(OmdbClient { _, _, _ ->
            requests++
            OmdbResult.Found(OmdbMovie("movie", 2002, 8.0, 100, "tt123"))
        }, store, { now }) {}
        val past = film().copy(start = now - Films.DAY, stop = now - Films.DAY + 120 * 60_000)
        assertNotNull(lookup.lookup(past, "fixture", now))
        assertNotNull(lookup.lookup(film(), "fixture", now))
        assertNull(lookup.lookup(past.copy(title = "Other film", originalTitle = "Other film"), "fixture", now))
        assertEquals(1, requests)
        assertEquals(Films.DAILY_BUDGET, store.used[Films.day(now)])
    }
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
        assertEquals(listOf(airing), rankedFilms(films, now, Films.LIVE_TAB))
        assertEquals(listOf(lowVotes), rankedFilms(films, now, Films.UPCOMING_TAB))
        assertEquals(listOf(tomorrow), rankedFilms(films, now, Films.FUTURE_TAB))
        assertTrue(rankedFilms(emptyList(), now, 0).isEmpty())
        assertEquals("niedz. 29.03 00:30", Films.time(now))
        assertEquals("pon. 30.03 01:30", Films.time(now + Films.DAY))
        assertEquals(Films.day(now) + 1, Films.day(now + Films.DAY))
    }

    @Test fun fourTabsHaveDisjointLiveAndFutureBoundaries() {
        fun at(start: Long, stop: Long = start + 7_200_000) = film().copy(start = start, stop = stop)
        assertTrue(Films.inWindow(at(now), now, Films.LIVE_TAB))
        assertFalse(Films.inWindow(at(now), now, Films.UPCOMING_TAB))
        assertFalse(Films.inWindow(at(now - 7_200_000, now), now, Films.LIVE_TAB))
        assertTrue(Films.inWindow(at(now + 1), now, Films.UPCOMING_TAB))
        assertTrue(Films.inWindow(at(now + Films.DAY - 1), now, Films.UPCOMING_TAB))
        assertFalse(Films.inWindow(at(now + Films.DAY), now, Films.UPCOMING_TAB))
        assertTrue(Films.inWindow(at(now + Films.DAY), now, Films.FUTURE_TAB))
        assertFalse(Films.inWindow(at(now + 2 * Films.DAY), now, Films.FUTURE_TAB))
        assertFalse(Films.inWindow(at(now), now, Films.ARCHIVE_TAB))
        assertFalse(Films.inWindow(at(now), now, -1))
    }

    @Test fun numericRatingsAreRequiredButUnratedFilmsStillGetEnriched() {
        val base = FilmShowing(channel(), film(), null)
        val variants = listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 8.0).mapIndexed { i, score ->
            base.copy(channel = channel().copy(id = i.toLong()),
                rating = FilmRatingEntity("k", score, 10L, "tt123", now))
        }
        assertEquals(listOf(variants.last()), rankedFilms(listOf(base) + variants, now, Films.LIVE_TAB))
        assertEquals(listOf(base.programme), ratingCandidates(listOf(base), now))
    }
}
