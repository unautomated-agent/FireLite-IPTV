package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.library.ChannelSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelSearchTest {
    @Test fun ignoresBlankQueries() {
        assertNull(ChannelSearch.pattern(""))
        assertNull(ChannelSearch.pattern("  \t "))
    }

    @Test fun normalizesPolishAndAsciiCase() {
        assertEquals("%zazolc gesla jazn%", ChannelSearch.pattern("  ZAŻÓŁĆ GĘŚLĄ JAŹŃ  "))
        assertEquals(ChannelSearch.pattern("ŁÓDŹ HD"), ChannelSearch.pattern("lodz hd"))
    }

    @Test fun treatsSqlWildcardsAsLiteralNameCharacters() {
        assertEquals("%100\\%\\_hd%", ChannelSearch.pattern("100%_HD"))
        assertEquals("%a\\\\b%", ChannelSearch.pattern("a\\b"))
        assertEquals("%o'brien%", ChannelSearch.pattern("O'Brien"))
    }
}
