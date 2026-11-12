package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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
        assertEquals(4, store.requests)
        assertEquals(2, store.rows.size)
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
}
