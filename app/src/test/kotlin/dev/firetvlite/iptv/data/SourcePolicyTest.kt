package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.library.SourcePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePolicyTest {
    @Test fun changedAppVersionOrParserRevisionRequiresOneSuccessfulReimport() {
        assertTrue(SourcePolicy.needsReimport(0, 0, 115, 1))
        assertTrue(SourcePolicy.needsReimport(114, 1, 115, 1))
        assertTrue(SourcePolicy.needsReimport(115, 0, 115, 1))
        assertFalse(SourcePolicy.needsReimport(115, 1, 115, 1))
        assertTrue(SourcePolicy.needsReimport(116, 1, 115, 1))
    }

    private val day = 24L * 60 * 60 * 1000
    private val now = 10 * day

    @Test fun freshPopulatedSourceSwitchesWithoutImport() {
        assertFalse(SourcePolicy.needsImport(now - 1, 2, now))
        assertFalse(SourcePolicy.needsImport(now - day, 2, now))
    }

    @Test fun emptyOrNeverImportedSourceNeedsImport() {
        assertTrue(SourcePolicy.needsImport(now, 0, now))
        assertTrue(SourcePolicy.needsImport(0, 2, now))
    }

    @Test fun staleSourceNeedsImport() {
        assertTrue(SourcePolicy.needsImport(now - day - 1, 2, now))
    }

    @Test fun clockMovingBackDoesNotExpireFreshSource() {
        assertFalse(SourcePolicy.needsImport(now + day, 2, now))
    }

    @Test fun firstUseFollowsClock() {
        assertEquals(now, SourcePolicy.nextUsedAt(0, now))
    }

    @Test fun repeatedOrBackwardsClockStillMakesSelectionMostRecent() {
        assertEquals(now + 1, SourcePolicy.nextUsedAt(now, now))
        assertEquals(now + 1, SourcePolicy.nextUsedAt(now, now - day))
    }
}
