package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.film.RankingStatus
import dev.firetvlite.iptv.data.film.rankingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FilmCatalogueTest {
    @Test fun initialQueryEpgImportAndEnrichmentShowLoadingNeverMissingEpg() {
        assertEquals(RankingStatus.Loading, rankingStatus(false, false, false, false, false))
        assertEquals(RankingStatus.Loading, rankingStatus(true, true, false, false, false))
        assertEquals(RankingStatus.Loading, rankingStatus(true, false, true, true, false))
        assertEquals(RankingStatus.Loading, rankingStatus(true, false, true, true, true))
    }

    @Test fun emptyWindowAndNonFilmEpgNeverAskToUpdateEpg() {
        assertEquals(RankingStatus.Empty, rankingStatus(true, false, true, false, false))
        assertEquals(RankingStatus.Ready, rankingStatus(true, false, true, false, true))
    }

    @Test fun missingGuideOrFailedImportWithoutExistingGuideOffersEpgRefresh() {
        assertEquals(RankingStatus.MissingEpg, rankingStatus(true, false, false, false, false))
    }
}
