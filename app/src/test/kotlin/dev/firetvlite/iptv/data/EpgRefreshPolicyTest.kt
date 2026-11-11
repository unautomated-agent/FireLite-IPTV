package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.epg.EpgRefreshAction
import dev.firetvlite.iptv.data.epg.EpgRefreshPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgRefreshPolicyTest {
    private val now = 1_800_000_000_000L

    @Test fun missingUrlsClearEvenFreshPublishedDataOrForcedRefresh() {
        assertEquals(EpgRefreshAction.Clear, EpgRefreshPolicy.action(false, now, now, false))
        assertEquals(EpgRefreshAction.Clear, EpgRefreshPolicy.action(false, 0, now, true))
    }

    @Test fun existingUrlsSkipFreshDataButImportStaleMissingOrForcedData() {
        assertEquals(EpgRefreshAction.Skip, EpgRefreshPolicy.action(true, now - EpgRefreshPolicy.MAX_AGE_MS, now, false))
        assertEquals(EpgRefreshAction.Import, EpgRefreshPolicy.action(true, now - EpgRefreshPolicy.MAX_AGE_MS - 1, now, false))
        assertEquals(EpgRefreshAction.Import, EpgRefreshPolicy.action(true, 0, now, false))
        assertEquals(EpgRefreshAction.Import, EpgRefreshPolicy.action(true, now, now, true))
    }
}
