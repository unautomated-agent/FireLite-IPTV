package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.film.*
import dev.firetvlite.iptv.data.library.DiagnosticsReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OmdbMissAnalysisTest {
    private val p = ProgrammeEntity(sourceId = 1, generation = "g", epgKey = "c", start = 0, stop = 7_200_000,
        title = "Polski film", description = null, category = "dramat", year = 1960)
    @Test fun onlyExplicitNonFilmCategoriesAndMissingUsableTitlesAreNewlySkipped() {
        for (category in listOf("Sports", "koncert", "teatr", "documentary series")) {
            assertFalse(category, Films.candidate(p.copy(categories = "dramat\n$category")))
        }
        assertFalse(Films.candidate(p.copy(title = "---", originalTitle = "...")))
        assertTrue(Films.candidate(p.copy(title = "", originalTitle = "Original")))
        for (category in listOf("dramat", "film telewizyjny", "film animowany", "film fabularny\ndokument", "film\ndla dzieci")) {
            assertTrue(category, Films.candidate(p.copy(categories = category, title = "Część II")))
        }
        assertFalse(Films.candidate(p.copy(year = null)))
    }
    @Test fun missCategoriesAreBoundedTitleFreeDailyAndSurviveQrCompaction() {
        val now = 1_800_000_000_000L
        val stats = OmdbStats().missing(p, now).missing(p.copy(categories = "dramat\ndokument"), now)
        assertEquals(1, stats.missTypes[OmdbMissType.OLD])
        assertEquals(1, stats.missTypes[OmdbMissType.DOCUMENTARY])
        assertTrue(stats.today(now + Films.DAY).missTypes.isEmpty())
        val summary = stats.missSummary()
        assertFalse(summary.contains(p.title))
        val qr = DiagnosticsReport.payload(summary + "\n" + "other ".repeat(1000))
        assertTrue(qr.contains(summary))
        assertTrue(qr.toByteArray().size <= 1800)
    }
    @Test fun onlyFreshNetworkMissingResponsesCountAndSkippedCandidatesMakeNoRequests() = runBlocking {
        var requests = 0
        var recorded = 0
        val rows = mutableMapOf<String, FilmRatingEntity>()
        val store = object : FilmStore {
            override suspend fun cached(key: String) = rows[key]
            override suspend fun save(rating: FilmRatingEntity) { rows[rating.cacheKey] = rating }
            override suspend fun reserve(day: Long) = true
        }
        val observer = object : OmdbObserver {
            override fun missing(programme: ProgrammeEntity, now: Long) { recorded++ }
        }
        val lookup = FilmLookup(OmdbClient { _, _, _ -> requests++; OmdbResult.Missing }, store,
            clock = { 0 }, observer = observer, wait = {})
        assertNull(lookup.lookup(p.copy(categories = "dramat\nsport"), "fixture", 0))
        assertEquals(0, requests)
        assertNotNull(lookup.lookup(p, "fixture", 0))
        assertNotNull(lookup.lookup(p, "fixture", 0))
        assertEquals(1, requests)
        assertEquals(1, recorded)
    }
}
