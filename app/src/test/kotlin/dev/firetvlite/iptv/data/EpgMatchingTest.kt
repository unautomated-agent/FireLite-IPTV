package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.epg.EpgMatching
import dev.firetvlite.iptv.data.epg.EpgSources
import dev.firetvlite.iptv.data.epg.EpgWindow
import dev.firetvlite.iptv.data.epg.XmlTvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgMatchingTest {
    @Test fun matchesPolishIdSuffixesTvgNamesAndCountryQualityVariants() {
        val index = EpgMatching.Index(listOf(XmlTvChannel("tvn", "TVN.PL ", listOf("TVN HD"))))
        assertEquals("tvn", index.match(" tvn ", "Other"))
        assertEquals("tvn", index.match("TVN.pl", "Other"))
        assertEquals("tvn", index.match(null, "Other", "PL: TVN UHD"))
        assertEquals("tvn", index.match(null, "TVN FHD"))
        assertNull(index.match("missing", "Other", "Unknown"))
    }

    @Test fun xtreamFallbackIsUsedOnlyWhenNoGuideWasSpecified() {
        val playlist = "https://host:8443/get.php?username=user&password=p%26w&type=m3u_plus"
        assertEquals(listOf("https://host:8443/xmltv.php?username=user&password=p%26w"), EpgSources.urls("", "", playlist))
        assertEquals(listOf("https://host:8443/a", "https://host:8443/b"), EpgSources.urls("", "a b,a", playlist))
    }

    @Test fun normalizesAccentsPunctuationWhitespaceAndQualitySuffixes() {
        assertEquals("lodz tv", EpgMatching.name("  ŁÓDŹ--TV   FHD / UHD (4K)  "))
        assertEquals("canal sport", EpgMatching.name("CANAL+ Sport HD"))
        assertEquals("hd radio", EpgMatching.name("HD Radio"))
    }

    @Test fun idWinsOverFallbackAcrossFeeds() {
        val index = EpgMatching.Index(listOf(
            XmlTvChannel("0:fallback", "other", listOf("Kanał HD")),
            XmlTvChannel("1:exact", " Exact.Id ", listOf("Different")),
        ))
        assertEquals("1:exact", index.match(" exact.ID ", "Kanał UHD"))
        assertEquals("0:fallback", index.match("unknown", "Kanal FHD"))
        assertEquals("0:fallback", index.match(null, "Kanał (4K)"))
        assertNull(index.match(null, "Missing"))
        assertNull(index.match("", ""))
    }

    @Test fun firstFeedBreaksDuplicateNameAndIdTies() {
        val index = EpgMatching.Index(listOf(
            XmlTvChannel("first", "id", listOf("Name")),
            XmlTvChannel("second", "ID", listOf("Name HD")),
        ))
        assertEquals("first", index.match("id", "Name"))
        assertEquals("first", index.match(null, "Name UHD"))
    }

    @Test fun manualUrlWinsAndHeaderSupportsMultipleAndRelativeUrls() {
        assertEquals(listOf("https://manual/guide"), EpgSources.urls(" https://manual/guide ", "https://ignored/a", "http://playlist/list"))
        assertEquals(listOf("http://playlist/a.xml", "https://other/b"),
            EpgSources.urls("", "a.xml, https://other/b\na.xml", "http://playlist/list"))
        assertTrue(EpgSources.urls("", "", "http://playlist/list").isEmpty())
        assertTrue(runCatching { EpgSources.urls("file:///guide", "", "http://playlist/list") }.isFailure)
    }

    @Test fun keepsAtLeastSevenDaysAndExtendsForCatchup() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(EpgWindow(-7 * day, 3 * day), EpgWindow.around(0, 2))
        assertEquals(EpgWindow(-14 * day, 3 * day), EpgWindow.around(0, 14))
        assertEquals(EpgWindow(-7 * day, 3 * day), EpgWindow.around(0, -1))
    }

    @Test fun usesHalfOpenOverlapIncludingBoundaryStraddlers() {
        val window = EpgWindow(10, 20)
        assertTrue(window.contains(5, 11))
        assertTrue(window.contains(19, 21))
        assertTrue(window.contains(10, 20))
        assertFalse(window.contains(5, 10))
        assertFalse(window.contains(20, 21))
        assertFalse(window.contains(15, 15))
        assertFalse(window.contains(16, 14))
    }
}
