package dev.firetvlite.iptv.data

import android.content.SharedPreferences
import dev.firetvlite.iptv.data.film.OmdbPreferences
import dev.firetvlite.iptv.data.film.OmdbResult
import dev.firetvlite.iptv.data.film.OmdbMovie
import dev.firetvlite.iptv.data.film.FilmLookup
import dev.firetvlite.iptv.data.film.FilmStore
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.film.OmdbClient
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import java.lang.reflect.Proxy
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OmdbPreferencesTest {
    @Test fun expiredQuotaStatusClearsEvenWhenEveryRemainingFilmIsCached() = runBlocking {
        val backend = Backend()
        val now = Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val preferences = OmdbPreferences(backend.preferences) { now }
        preferences.completed(OmdbResult.Failed("REMOTE_LIMIT"), now)
        assertEquals(1L, preferences.quotaDelay(midnight - 1))
        assertEquals("REMOTE_LIMIT", preferences.currentError.value)
        assertEquals(0L, preferences.quotaDelay(midnight))
        val programme = ProgrammeEntity(sourceId = 1, generation = "g", epgKey = "c", start = midnight,
            stop = midnight + 7_200_000, title = "Film", description = null, category = "dramat", year = 2002)
        val cached = FilmRatingEntity(Films.key(programme), 7.1, 100L, "tt123", now)
        val lookup = FilmLookup(OmdbClient { _, _, _ -> error("Cached film must not make a request") },
            object : FilmStore {
                override suspend fun cached(key: String) = cached
                override suspend fun save(rating: FilmRatingEntity) = error("Cached film must not be saved again")
                override suspend fun reserve(day: Long): Boolean = error("Cached film must not reserve budget")
            }, { midnight }, preferences, {})
        assertEquals(cached, lookup.lookup(programme, "fixture", midnight))
        assertNull(preferences.currentError.value)
        val reopened = OmdbPreferences(backend.preferences) { midnight }
        assertNull(reopened.currentError.value)
        assertEquals("REMOTE_LIMIT", backend.preferences.getString("last_error", null))
    }

    @Test fun reopeningAfterQuotaExpiryClearsOnlyTheCurrentQuotaStatus() {
        val backend = Backend()
        val now = Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        OmdbPreferences(backend.preferences) { now }.completed(OmdbResult.Failed("REMOTE_LIMIT"), now)
        val reopened = OmdbPreferences(backend.preferences) { midnight }
        assertNull(reopened.currentError.value)
        assertNull(backend.preferences.getString("current_error", null))
        assertEquals("REMOTE_LIMIT", backend.preferences.getString("last_error", null))
        reopened.error("AUTH", midnight)
        assertEquals(0L, reopened.quotaDelay(midnight))
        assertEquals("AUTH", reopened.currentError.value)
        assertEquals("AUTH", OmdbPreferences(backend.preferences) { midnight }.currentError.value)
    }

    @Test fun remoteQuotaPauseSurvivesRestartAndSameKeySaveUntilWarsawMidnightAcrossDst() {
        val backend = Backend()
        val now = Instant.parse("2026-03-28T23:30:00Z").toEpochMilli()
        val midnight = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val preferences = OmdbPreferences(backend.preferences) { now }
        preferences.save("fixture")
        assertEquals(0L, preferences.quotaDelay(now))
        preferences.completed(OmdbResult.Failed("REMOTE_LIMIT"), now)
        assertEquals(midnight - now, preferences.quotaDelay(now))
        val reopened = OmdbPreferences(backend.preferences) { now }
        assertEquals(midnight - now, reopened.quotaDelay(now))
        reopened.save("fixture")
        assertEquals("REMOTE_LIMIT", reopened.currentError.value)
        assertEquals(1L, reopened.quotaDelay(midnight - 1))
        assertEquals(0L, reopened.quotaDelay(midnight))
        assertEquals(0L, reopened.quotaDelay(midnight + 1))
        reopened.save("different-fixture")
        assertEquals(0L, reopened.quotaDelay(now))
        assertNull(reopened.currentError.value)
    }

    @Test fun invalidKeyAndNetworkErrorsDoNotSetADailyQuotaPause() {
        val preferences = OmdbPreferences(Backend().preferences)
        for (code in listOf("AUTH", "HTTP_401", "NETWORK")) {
            preferences.completed(OmdbResult.Failed(code), 1_800_000_000_000L)
            assertEquals(0L, preferences.quotaDelay(1_800_000_000_000L))
        }
    }
    @Test fun sampleUrlIsNormalizedAndActivationLinkCannotOverwriteAnExistingKeyOrRefresh() {
        val backend = Backend()
        val preferences = OmdbPreferences(backend.preferences)
        assertTrue(preferences.save("https://www.omdbapi.com/?apikey=fixture&t=Movie"))
        assertEquals("fixture", preferences.key.value)
        val refresh = preferences.refresh.value
        assertFalse(preferences.save("https://www.omdbapi.com/apikey.aspx?VERIFYKEY=activation-token"))
        assertEquals("fixture", preferences.key.value)
        assertEquals("fixture", OmdbPreferences(backend.preferences).key.value)
        assertEquals(refresh, preferences.refresh.value)
    }

    @Test fun successfulLookupClearsCurrentFailureButRetainsLastErrorForDiagnostics() {
        val backend = Backend()
        val now = 1_800_000_000_000L
        val preferences = OmdbPreferences(backend.preferences) { now }
        preferences.error("HTTP_401", now)
        preferences.completed(OmdbResult.Found(OmdbMovie("movie", 2002, 7.1, 100, "tt123")), now)
        assertNull(preferences.currentError.value)
        assertNull(OmdbPreferences(backend.preferences) { now }.currentError.value)
        assertEquals("HTTP_401", preferences.stats.value.lastError)
        assertEquals(1, preferences.stats.value.hits)
        preferences.error("not a safe code", now)
        assertEquals("UNKNOWN", preferences.currentError.value)
    }

    @Test fun savingTheSameKeyAfterActivationRetriggersLookupAndClearsOnlyCurrentError() {
        val backend = Backend()
        val now = 1_800_000_000_000L
        val preferences = OmdbPreferences(backend.preferences) { now }
        preferences.save("fixture")
        val firstRefresh = preferences.refresh.value
        preferences.completed(OmdbResult.Failed("HTTP_401"), now)
        assertEquals("HTTP_401", preferences.currentError.value)
        assertEquals("HTTP_401", OmdbPreferences(backend.preferences) { now }.currentError.value)
        preferences.save("fixture")
        assertEquals("fixture", preferences.key.value)
        assertEquals(firstRefresh + 1, preferences.refresh.value)
        assertNull(preferences.currentError.value)
        assertEquals("HTTP_401", preferences.stats.value.lastError)
        preferences.completed(OmdbResult.Failed("AUTH"), now)
        preferences.completed(OmdbResult.Missing, now)
        assertNull(preferences.currentError.value)
        assertEquals("AUTH", preferences.stats.value.lastError)
        preferences.save("")
        assertTrue(preferences.key.value.isBlank())
        assertEquals(firstRefresh + 2, preferences.refresh.value)
    }

    @Test fun requestStatsPersistWithoutCountingKeyConfigurationAsValidation() {
        val backend = Backend()
        val now = 1_800_000_000_000L
        val preferences = OmdbPreferences(backend.preferences) { now }
        assertTrue(preferences.key.value.isBlank())
        assertEquals(0, preferences.stats.value.requests)
        preferences.save(" fixture ")
        assertEquals("fixture", preferences.key.value)
        assertEquals(0, preferences.stats.value.requests)
        preferences.requested(now)
        preferences.completed(OmdbResult.Missing, now)
        preferences.requested(now)
        preferences.completed(OmdbResult.Failed("AUTH"), now)
        val reopened = OmdbPreferences(backend.preferences) { now }
        assertEquals(preferences.stats.value, reopened.stats.value)
        assertEquals(2, reopened.stats.value.requests)
        assertEquals(1, reopened.stats.value.misses)
        assertEquals("AUTH", reopened.stats.value.lastError)
        val tomorrow = OmdbPreferences(backend.preferences) { now + 86_400_000 }
        assertEquals(0, tomorrow.stats.value.requests)
        assertEquals("fixture", tomorrow.key.value)
    }

    private class Backend {
        private val values = mutableMapOf<String, Any?>()
        val preferences: SharedPreferences
        init {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when (method.name) {
                    "putLong", "putInt", "putString" -> { values[args!![0] as String] = args[1]; editor }
                    "apply" -> null
                    else -> error("Unexpected editor method: ${method.name}")
                }
            } as SharedPreferences.Editor
            preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
                arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getLong", "getInt", "getString" -> values[args!![0] as String] ?: args[1]
                    "edit" -> editor
                    else -> error("Unexpected preferences method: ${method.name}")
                }
            } as SharedPreferences
        }
    }
}
