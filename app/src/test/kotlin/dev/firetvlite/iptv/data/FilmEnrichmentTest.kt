package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class FilmEnrichmentTest {
    private val now = 1_800_000_000_000L
    private fun film(title: String) = ProgrammeEntity(sourceId = 1, generation = "g", epgKey = "c",
        start = now, stop = now + 7_200_000, title = title, description = null, category = "dramat", year = 2002)
    private val found = OmdbResult.Found(OmdbMovie("movie", 2002, 7.8, 123, "tt123"))
    private class Store : FilmStore {
        val rows = mutableMapOf<String, FilmRatingEntity>()
        var requests = 0
        override suspend fun cached(key: String) = rows[key]
        override suspend fun save(rating: FilmRatingEntity) { rows[rating.cacheKey] = rating }
        override suspend fun reserve(day: Long) = if (requests < 400) { requests++; true } else false
    }

    @Test fun transientFailureDoesNotStarveLaterFilmsAndIsRetriedWithoutPoisoningCache() = runBlocking {
        val store = Store()
        var fail = true
        val client = OmdbClient { _, title, _ -> if (title == "First" && fail) OmdbResult.Failed("HTTP_503") else found }
        val lookup = FilmLookup(client, store, { now }) {}
        val candidates = listOf(film("First"), film("Second"))
        assertTrue(enrichFilms(candidates, "fixture", lookup, { true }) { now })
        assertNull(store.rows[Films.key(candidates[0])])
        assertEquals(7.8, store.rows[Films.key(candidates[1])]!!.imdbRating!!, 0.0)
        fail = false
        assertFalse(enrichFilms(candidates, "fixture", lookup, { true }) { now })
        assertEquals(3, store.requests)
        assertEquals(2, store.rows.size)
    }

    @Test fun globalFailuresStopRequestsButMissingFilmsDoNot() = runBlocking {
        for (code in listOf("AUTH", "REMOTE_LIMIT", "HTTP_401", "HTTP_403", "HTTP_429")) {
            val store = Store()
            val lookup = FilmLookup(OmdbClient { _, _, _ -> OmdbResult.Failed(code) }, store, { now }) {}
            assertFalse(enrichFilms(listOf(film("First"), film("Second")), "fixture", lookup, { true }) { now })
            assertEquals(1, store.requests)
            assertTrue(store.rows.isEmpty())
        }
        val store = Store()
        val lookup = FilmLookup(OmdbClient { _, title, _ -> if (title == "First") OmdbResult.Missing else found }, store, { now }) {}
        assertFalse(enrichFilms(listOf(film("First"), film("Second")), "fixture", lookup, { true }) { now })
        assertEquals(2, store.requests)
        assertEquals(3, store.rows.size)
    }

    @Test fun interruptedFallbackResumesOnlyTheUnfinishedVariantAfterRecreatingLookup() = runBlocking {
        for (failure in listOf("HTTP_503", "REMOTE_LIMIT", "AUTH", "HTTP_401")) {
            for (first in listOf(OmdbResult.Missing, found.copy(movie = found.movie.copy(type = "series")))) {
                val store = Store()
                val programme = film("Display").copy(originalTitle = "Original")
                val queries = mutableListOf<Pair<String, Int>>()
                val interrupted = FilmLookup(OmdbClient { _, title, year ->
                    queries.add(title to year)
                    if (title == "Original") first else OmdbResult.Failed(failure)
                }, store, { now }) {}
                assertNull(interrupted.lookup(programme, "fixture", now))
                assertNull(store.rows[Films.key(programme)])
                val resumed = FilmLookup(OmdbClient { _, title, year ->
                    queries.add(title to year)
                    found
                }, store, { now + 1000 }) {}
                assertEquals("tt123", resumed.lookup(programme, "fixture", now + 1000)!!.imdbId)
                assertEquals(listOf("Original" to 2002, "Display" to 2002, "Display" to 2002), queries)
                assertEquals(3, store.requests)
            }
        }
    }

    @Test fun cancellationAfterAMissingResponseStillPersistsThatVariant() = runBlocking {
        val store = Store()
        val programme = film("Display").copy(originalTitle = "Original")
        val queries = mutableListOf<String>()
        val interrupted = FilmLookup(OmdbClient { _, title, _ ->
            queries.add(title)
            currentCoroutineContext().cancel()
            OmdbResult.Missing
        }, store, { now }) {}
        launch { interrupted.lookup(programme, "fixture", now) }.join()
        assertEquals(listOf("Original"), queries)
        assertNull(store.rows[Films.key(programme)])
        val resumed = FilmLookup(OmdbClient { _, title, _ -> queries.add(title); found }, store, { now }) {}
        assertNotNull(resumed.lookup(programme, "fixture", now))
        assertEquals(listOf("Original", "Display"), queries)
    }

    @Test fun completedMissesAndVariantMissesExpireTogetherAfterThirtyDays() = runBlocking {
        val store = Store()
        val programme = film("Display").copy(originalTitle = "Original")
        var time = now
        val lookup = FilmLookup(OmdbClient { _, _, _ -> OmdbResult.Missing }, store, { time }) {}
        assertNull(lookup.lookup(programme, "fixture", time)!!.imdbId)
        assertEquals(2, store.requests)
        time += 30 * Films.DAY - 1
        lookup.lookup(programme, "fixture", time)
        assertEquals(2, store.requests)
        time++
        lookup.lookup(programme, "fixture", time)
        assertEquals(4, store.requests)
    }

    @Test fun partialProgressAlsoExpiresAfterThirtyDays() = runBlocking {
        val store = Store()
        val programme = film("Display").copy(originalTitle = "Original")
        val interrupted = FilmLookup(OmdbClient { _, title, _ ->
            if (title == "Original") OmdbResult.Missing else OmdbResult.Failed("HTTP_503")
        }, store, { now }) {}
        interrupted.lookup(programme, "fixture", now)
        val queries = mutableListOf<String>()
        val resumed = FilmLookup(OmdbClient { _, title, _ -> queries.add(title); OmdbResult.Missing },
            store, { now + 30 * Films.DAY }) {}
        resumed.lookup(programme, "fixture", now + 30 * Films.DAY)
        assertEquals(listOf("Original", "Display"), queries)
    }

    @Test fun repeatedTabRequestsShareOneRunningPassAndOneRequestPerUncachedFilm() = runBlocking {
        val requests = FilmWindowRequests()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val store = Store()
        val lookup = FilmLookup(OmdbClient { _, _, _ ->
            started.complete(Unit)
            finish.await()
            found
        }, store, { now }) {}
        var passes = 0
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            requests.pending.collectLatest { request ->
                if (request != null) {
                    passes++
                    enrichFilms(listOf(film("Future")), "fixture", lookup, { true }) { now }
                    requests.complete(request)
                    completed.complete(Unit)
                }
            }
        }
        try {
            withTimeout(5000) {
                requests.request(1, 0)
                started.await()
                repeat(20) { requests.request(1, 0) }
                yield()
                assertEquals(1, passes)
                assertEquals(1, store.requests)
                finish.complete(Unit)
                completed.await()
                assertNull(requests.pending.value)
                assertEquals(1, passes)
            }
        } finally { worker.cancel(); worker.join() }
    }

    @Test fun tabRequestsAreBoundToSourceAndKeyRevisionAndOldCompletionCannotClearANewOne() {
        val requests = FilmWindowRequests()
        requests.request(1, 0)
        val old = requests.pending.value!!
        assertTrue(old.matches(1, 0))
        assertFalse(old.matches(null, 0))
        assertFalse(old.matches(2, 0))
        assertFalse(old.matches(1, 1))
        requests.request(2, 1)
        requests.complete(old)
        assertTrue(requests.pending.value!!.matches(2, 1))
        requests.complete(requests.pending.value!!)
        assertNull(requests.pending.value)
    }

    @Test fun emptyNoKeyChangedSourceCancellationAndExhaustedBudgetNeverOpenRequests() = runBlocking {
        val store = Store()
        val lookup = FilmLookup(OmdbClient { _, _, _ -> fail("Unexpected request"); found }, store, { now }) {}
        assertFalse(enrichFilms(emptyList(), "fixture", lookup, { true }) { now })
        assertFalse(enrichFilms(listOf(film("First")), "fixture", lookup, { false }) { now })
        assertFalse(enrichFilms(listOf(film("First")), "", lookup, { true }) { now })
        assertEquals("NO_KEY", lookup.lastError)
        store.requests = 400
        assertFalse(enrichFilms(listOf(film("First")), "fixture", lookup, { true }) { now })
        assertEquals("LOCAL_BUDGET", lookup.lastError)
        assertEquals(400, store.requests)
        assertFalse(enrichFilms(emptyList(), "fixture", lookup, { true }) { now })
        assertNull(lookup.lastError)
        try {
            enrichFilms(listOf(film("First")), "fixture", lookup, { throw CancellationException() }) { now }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) { }
    }

    @Test fun statsCountHttpAttemptsNotCacheHitsResetAtMidnightAndNeverStoreRawErrors() = runBlocking {
        var stats = OmdbStats()
        val observer = object : OmdbObserver {
            override fun requested(now: Long) { stats = stats.requested(now) }
            override fun completed(result: OmdbResult, now: Long) { stats = stats.completed(result, now) }
            override fun error(code: String, now: Long) { stats = stats.today(now).error(code) }
        }
        val store = Store()
        val lookup = FilmLookup(OmdbClient { _, _, _ -> found }, store, { now }, observer) {}
        lookup.lookup(film("First"), "fixture", now)
        lookup.lookup(film("First"), "fixture", now)
        assertEquals(1, stats.requests)
        assertEquals(1, stats.hits)
        stats = stats.requested(now).completed(OmdbResult.Missing, now)
            .requested(now).completed(OmdbResult.Failed("AUTH"), now)
        assertEquals(3, stats.requests)
        assertEquals(1, stats.misses)
        assertEquals("AUTH", stats.lastError)
        assertEquals("UNKNOWN", stats.error("https://secret.invalid/?key=secret").lastError)
        assertEquals(OmdbStats(Films.day(now + Films.DAY)), stats.today(now + Films.DAY))
    }

    @Test fun resumedProgressDoesNotCountSkippedVariantsAsHttpRequestsOrMisses() = runBlocking {
        var stats = OmdbStats()
        val observer = object : OmdbObserver {
            override fun requested(now: Long) { stats = stats.requested(now) }
            override fun completed(result: OmdbResult, now: Long) { stats = stats.completed(result, now) }
        }
        val store = Store()
        val programme = film("Display").copy(originalTitle = "Original")
        val interrupted = FilmLookup(OmdbClient { _, title, _ ->
            if (title == "Original") OmdbResult.Missing else OmdbResult.Failed("HTTP_503")
        }, store, { now }, observer) {}
        interrupted.lookup(programme, "fixture", now)
        assertEquals(OmdbStats(Films.day(now), 2, 0, 1, "HTTP_503"), stats)
        val resumed = FilmLookup(OmdbClient { _, _, _ -> found }, store, { now }, observer) {}
        resumed.lookup(programme, "fixture", now)
        resumed.lookup(programme, "fixture", now)
        assertEquals(OmdbStats(Films.day(now), 3, 1, 1, "HTTP_503"), stats)
    }
}
