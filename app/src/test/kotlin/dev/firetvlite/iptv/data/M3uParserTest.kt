package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.playlist.M3uParser
import dev.firetvlite.iptv.data.playlist.ParsedChannel
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test fun detectsArchiveAliasesDefaultsExplicitOptOutAndXtreamPaths() {
        val rows = parse("""
            #EXTM3U catchup-type='append' catchup-days=3 catchup-source='?start=${'$'}{start}&end=${'$'}{end}'
            #EXTINF:-1,Default
            http://h/1
            #EXTINF:-1 catchup-days=0,Disabled
            http://h/live/u/p/2.ts
            #EXTINF:-1 catchup-type=xc tvg-rec=7,XC
            http://h/live/u/p/3.m3u8
            #EXTINF:-1 catchup=shift catchup-time=172800,Seconds
            http://h/4
        """.trimIndent())
        assertEquals("append", rows[0].catchupType)
        assertEquals(3, rows[0].catchupDays)
        assertEquals(0, rows[1].catchupDays)
        assertEquals("xc", rows[2].catchupType)
        assertEquals(7, rows[2].catchupDays)
        assertEquals(2, rows[3].catchupDays)
        assertEquals("xc", parse("#EXTINF:-1 timeshift=5,XC\nhttps://h/u/p/10.ts").single().catchupType)
        assertEquals("shift", parse("#EXTINF:-1 tvg-rec=2,Shift\nhttps://h/channel").single().catchupType)
    }

    @Test fun discoversMixedCaseSingleQuotedBareAndSeparateHeaderUrls() {
        var urls = emptyList<String>()
        val rows = mutableListOf<ParsedChannel>()
        M3uParser.parse(StringReader("""
            #extm3u TVG-URL = 'http://a/epg http://b/epg,http://a/epg'
            #extvlcopt:URL-TVG=http://c/epg
            #X-TVG-URL="http://d/epg"
            #EXTINF:-1 TVG-ID=' TVN.PL ' tvg-name='TVN, HD',TVN display
            http://host/live/a/b/1.ts
        """.trimIndent()), onEpgUrls = { urls = it }) { rows.add(it) }
        assertEquals(listOf("http://a/epg", "http://b/epg", "http://c/epg", "http://d/epg"), urls)
        assertEquals("TVN, HD", rows.single().tvgName)
        assertEquals("TVN display", rows.single().name)
        assertEquals("TVN.PL", rows.single().tvgId)
    }


    private fun parse(text: String): List<ParsedChannel> {
        val channels = ArrayList<ParsedChannel>()
        M3uParser.parse(StringReader(text)) { channels.add(it) }
        return channels
    }

    @Test
    fun readsMultipleEpgHeaderUrlsAndAliases() {
        val urls = ArrayList<String>()
        M3uParser.parse(StringReader("\uFEFF#EXTM3U X-TVG-URL=\"http://a/guide, http://b/guide\" url-tvg=\"http://c/guide,http://a/guide\"\n"),
            onEpgUrls = { urls.addAll(it) }, onChannel = {},
        )
        assertEquals(listOf("http://a/guide", "http://b/guide", "http://c/guide"), urls)
    }

    @Test
    fun readsChannelsAndGroups() {
        val channels = parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" group-title=\"News\",Channel A\n" +
                "http://h/a.m3u8\n" +
                "#EXTINF:-1 group-title=\"Sport\",Channel B\n" +
                "http://h/b.ts\n",
        )

        assertEquals(2, channels.size)
        assertEquals("Channel A", channels[0].name)
        assertEquals("News", channels[0].group)
        assertEquals("a", channels[0].tvgId)
        assertEquals("http://h/a.m3u8", channels[0].url)
        assertEquals("Sport", channels[1].group)
    }

    @Test
    fun readsTheLogo() {
        val channels = parse("#EXTINF:-1 tvg-logo=\"http://h/logo.png\",One\nhttp://h/1\n")

        assertEquals("http://h/logo.png", channels[0].logoUrl)
    }

    @Test
    fun aCommaInsideQuotesDoesNotSplitTheName() {
        val channels = parse("#EXTINF:-1 tvg-name=\"A, B\" group-title=\"X, Y\",Name, with comma\nhttp://h/1\n")

        assertEquals("Name, with comma", channels[0].name)
        assertEquals("X, Y", channels[0].group)
    }

    @Test
    fun extgrpIsUsedWhenThereIsNoGroupTitle() {
        assertEquals("Kino", parse("#EXTINF:-1,One\n#EXTGRP:Kino\nhttp://h/1\n")[0].group)
        assertEquals("A", parse("#EXTINF:-1 group-title=\"A\",One\n#EXTGRP:B\nhttp://h/1\n")[0].group)
    }

    @Test
    fun aPlainUrlListUsesTheUrlAsName() {
        val channels = parse("http://h/1\nhttp://h/2\n")

        assertEquals(2, channels.size)
        assertEquals("http://h/1", channels[0].name)
        assertEquals("", channels[0].group)
    }

    @Test
    fun handlesBomAndWindowsLineEndings() {
        val channels = parse("\uFEFF#EXTM3U\r\n#EXTINF:-1,One\r\nhttp://h/1\r\n")

        assertEquals(1, channels.size)
        assertEquals("One", channels[0].name)
    }

    @Test
    fun ignoresOptionsAndLinesThatAreNotUrls() {
        val channels = parse("#EXTM3U\n#EXTVLCOPT:http-user-agent=x\n<html><a href=\"http://x\">no</a></html>\n")

        assertTrue(channels.isEmpty())
    }

    @Test
    fun attributeNamesAreCaseInsensitive() {
        assertEquals("Kids", parse("#EXTINF:-1 Group-Title=\"Kids\",Toon\nhttp://h/1\n")[0].group)
    }

    @Test
    fun anEmptyNameFallsBackToTvgName() {
        assertEquals("Fallback", parse("#EXTINF:-1 tvg-name=\"Fallback\",\nhttp://h/x\n")[0].name)
    }

    @Test
    fun infoDoesNotLeakIntoTheNextChannel() {
        val channels = parse("#EXTINF:-1 group-title=\"G\",One\nhttp://h/1\nhttp://h/2\n")

        assertEquals("G", channels[0].group)
        assertEquals("", channels[1].group)
        assertEquals("http://h/2", channels[1].name)
    }

    @Test
    fun readsCatchupSettingsOfAChannel() {
        val channels = parse(
            "#EXTINF:-1 catchup=\"append\" catchup-days=\"3\" catchup-source=\"?utc={utc}\",One\nhttp://h/1\n",
        )

        assertEquals("append", channels[0].catchupType)
        assertEquals("?utc={utc}", channels[0].catchupSource)
        assertEquals(3, channels[0].catchupDays)
    }

    @Test
    fun usesCatchupDefaultsFromTheHeader() {
        val channels = parse(
            "#EXTM3U catchup=\"shift\" catchup-days=\"7\"\n#EXTINF:-1,One\nhttp://h/1\n" +
                "#EXTINF:-1 catchup=\"default\" catchup-days=\"2\" catchup-source=\"http://h/{utc}\",Two\nhttp://h/2\n",
        )

        assertEquals("shift", channels[0].catchupType)
        assertEquals(7, channels[0].catchupDays)
        assertEquals("default", channels[1].catchupType)
        assertEquals(2, channels[1].catchupDays)
    }

    @Test
    fun aChannelWithoutCatchupHasNone() {
        val channel = parse("#EXTINF:-1,One\nhttp://h/1\n")[0]

        assertNull(channel.catchupType)
        assertEquals(0, channel.catchupDays)
    }

    @Test
    fun tvgRecCountsAsCatchupDays() {
        assertEquals(5, parse("#EXTINF:-1 catchup=\"shift\" tvg-rec=\"5\",One\nhttp://h/1\n")[0].catchupDays)
    }
}
