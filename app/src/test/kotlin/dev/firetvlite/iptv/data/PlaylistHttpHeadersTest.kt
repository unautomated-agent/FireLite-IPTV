package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.playlist.M3uParser
import dev.firetvlite.iptv.data.playlist.ParsedChannel
import dev.firetvlite.iptv.data.playlist.PlaylistHttpHeaders
import org.junit.Assert.*
import org.junit.Test

class PlaylistHttpHeadersTest {
    private fun channel(text: String): ParsedChannel {
        val channels = mutableListOf<ParsedChannel>()
        M3uParser.parse(text.reader(), onChannel = { channels += it })
        return channels.single()
    }

    @Test fun vlcAttributesKodiAndSuffixHeadersArePreservedWithSuffixPrecedence() {
        val parsed = channel("""
            #EXTM3U
            #EXTINF:-1 tvg-user-agent="Attribute UA" http-referrer="https://fixture.test/player",Test
            #EXTVLCOPT:http-user-agent=VLC UA
            #KODIPROP:inputstream.adaptive.stream_headers=Origin=https%3A%2F%2Ffixture.test&Cookie=session%3Dtest
            #EXTGRP:News
            https://fixture.test/a%2Fb.ts?token=a%2Bb&x=1&x=2&tail=|User-Agent=Suffix%2F1.0&Referer=https%3A%2F%2Ffixture.test%2Fwatch
        """.trimIndent())
        assertEquals("https://fixture.test/a%2Fb.ts?token=a%2Bb&x=1&x=2&tail=", parsed.url)
        assertEquals(mapOf("User-Agent" to "Suffix/1.0", "Referer" to "https://fixture.test/watch",
            "Origin" to "https://fixture.test", "Cookie" to "session=test"), parsed.headers)
        assertEquals("News", parsed.group)
        assertEquals(parsed.headers, PlaylistHttpHeaders.decode(PlaylistHttpHeaders.encode(parsed.headers)))
    }

    @Test fun channelHeadersDoNotLeakToTheNextChannel() {
        val channels = mutableListOf<ParsedChannel>()
        M3uParser.parse("""
            #EXTINF:-1,First
            #EXTVLCOPT:http-user-agent=Fixture
            #EXTVLCOPT:http-referrer=https://fixture.test/
            https://fixture.test/first.m3u8
            #EXTINF:-1,Second
            https://fixture.test/second.ts
        """.trimIndent().reader(), onChannel = { channels += it })
        assertEquals("Fixture", channels.first().headers["User-Agent"])
        assertEquals("https://fixture.test/", channels.first().headers["Referer"])
        assertTrue(channels.last().headers.isEmpty())
    }

    @Test fun plainUrlsRemainByteForByteIncludingExtensionsEncodingQueryOrderAndTrailingParameters() {
        for (url in listOf("https://fixture.test/live.ts?a=%2F&token=x%2By&tail=", "https://fixture.test/a.m3u8?x=1&x=2",
            "https://fixture.test/no-extension;parameter?z=%25", "https://fixture.test/a%20b/")) {
            assertEquals(url, channel("#EXTINF:-1,Test\n$url").url)
        }
    }

    @Test fun emptyMalformedAndUnsafeHeadersAreIgnored() {
        assertEquals(emptyMap<String, String>(), PlaylistHttpHeaders.decode(""))
        assertEquals(emptyMap<String, String>(), PlaylistHttpHeaders.decode("not-json"))
        val headers = linkedMapOf<String, String>()
        PlaylistHttpHeaders.parameters("User-Agent=ok%0D%0AX-Evil%3Ayes&Host=evil&Authorization=Bearer+fixture&Referer=%ZZ", headers)
        assertEquals(mapOf("Authorization" to "Bearer fixture"), headers)
        assertTrue(PlaylistHttpHeaders.splitUrl("https://fixture.test/a").second.isEmpty())
    }

    @Test fun kodiManifestHeadersAndPlainHttpAttributesAreSupported() {
        assertEquals("Kodi UA", channel("""
            #EXTINF:-1 user-agent="Attr",Test
            #KODIPROP:inputstream.adaptive.manifest_headers=User-Agent=Kodi+UA
            https://fixture.test/a
        """.trimIndent()).headers["User-Agent"])
    }
}
