package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.epg.EpgWindow
import dev.firetvlite.iptv.data.epg.XmlTvParser
import dev.firetvlite.iptv.data.epg.XmlTvProgramme
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlTvParserTest {
    @Test fun streamsLargeGuideAndCanonicalizesProgrammeChannelIdsForMatching() = runBlocking {
        val xml = "<tv><channel id='TVN.PL'><display-name>TVN</display-name></channel>" +
            "<programme channel='tvn.pl ' start='20261111193000' stop='20261111203000 +0000'><title>News</title></programme>".repeat(10_000) + "</tv>"
        var count = 0
        var key = ""
        XmlTvParser.parse(ByteArrayInputStream(xml.toByteArray()), EpgWindow(Long.MIN_VALUE, Long.MAX_VALUE),
            onChannel = { id, _ -> key = dev.firetvlite.iptv.data.epg.EpgMatching.id(id) },
            onProgramme = { assertEquals(key, dev.firetvlite.iptv.data.epg.EpgMatching.id(it.channelId)); count++ })
        assertEquals(10_000, count)
    }

    private val now = Instant.parse("2026-11-11T19:30:00Z").toEpochMilli()
    private val window = EpgWindow.around(now, 7)
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id=" One "><display-name>Óne HD</display-name><display-name lang="pl">Jeden</display-name></channel>
          <programme channel="One" start="20261111203000 +0100" stop="20261111213000 +0100">
            <title lang="en">First</title><title lang="en">Ignored</title>
            <title lang="pl">Wiadomości &amp; sport</title><title lang="pl">Nie zastępuj</title>
            <desc><![CDATA[Opis <audycji>]]></desc><category>Informacje</category><category>Druga</category>
          </programme>
          <programme channel="One" start="20261111203000" stop="20261111213000"><title>First title</title><title>Second</title></programme>
        </tv>
    """.trimIndent()

    @Test fun parsesOffsetsAndUtcDefault() {
        assertEquals(now, XmlTvParser.time("20261111203000 +0100"))
        assertEquals(now, XmlTvParser.time("20261111143000 -0500"))
        assertEquals(now, XmlTvParser.time("20261111193000"))
        assertEquals(now, XmlTvParser.time("202611111930 Z"))
        assertEquals(now, XmlTvParser.time(" 20261111203000 +01:00 "))
    }

    @Test fun invalidTimesAreNotInvented() {
        listOf(null, "", "20260230120000", "20261111253000", "20261111203000 +2500", "yesterday")
            .forEach { assertNull(XmlTvParser.time(it)) }
    }

    private fun parse(bytes: ByteArray): Pair<List<String>, List<XmlTvProgramme>> = runBlocking {
        val names = ArrayList<String>()
        val programmes = ArrayList<XmlTvProgramme>()
        XmlTvParser.parse(ByteArrayInputStream(bytes), window,
            onChannel = { id, displayNames -> names.add(id); names.addAll(displayNames) },
            onProgramme = { programmes.add(it) },
        )
        names to programmes
    }

    @Test fun streamsPlainXmlAndPrefersFirstPolishTitle() {
        val (names, programmes) = parse(sample.toByteArray())
        assertEquals(listOf("One", "Óne HD", "Jeden"), names)
        assertEquals(2, programmes.size)
        assertEquals(now, programmes[0].start)
        assertEquals("Wiadomości & sport", programmes[0].title)
        assertEquals("Opis <audycji>", programmes[0].description)
        assertEquals("Informacje", programmes[0].category)
        assertEquals("First title", programmes[1].title)
        assertNull(programmes[1].description)
    }

    @Test fun gzipIsDetectedFromBytes() {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(sample.toByteArray()) }
        assertEquals(parse(sample.toByteArray()), parse(output.toByteArray()))
    }

    @Test fun emptyGuideIsValid() {
        assertTrue(parse("<tv/>".toByteArray()).second.isEmpty())
    }

    @Test fun acceptsXmltvDoctypeWithoutLoadingExternalDtd() {
        val xml = sample.replace("<tv>", "<!DOCTYPE tv SYSTEM \"file:///nonexistent-xmltv.dtd\"><tv>")
        assertEquals(2, parse(xml.toByteArray()).second.size)
    }

    @Test fun filtersOldFutureInvalidAndMissingStops() {
        val xml = """<tv>
            <programme channel="a" start="20200101100000" stop="20200101110000"><title>Old</title></programme>
            <programme channel="a" start="20300101100000" stop="20300101110000"><title>Future</title></programme>
            <programme channel="a" start="20261111100000"><title>No stop</title></programme>
            <programme channel="a" start="bad" stop="20261111110000"><title>Bad</title></programme>
            <programme channel="a" start="20261111110000" stop="20261111100000"><title>Reversed</title></programme>
            </tv>"""
        assertTrue(parse(xml.toByteArray()).second.isEmpty())
    }

    @Test fun malformedGuideFailsEvenAfterValidProgrammes() {
        assertTrue(runCatching { parse(sample.substringBeforeLast("</tv>").toByteArray()) }.isFailure)
        assertTrue(runCatching { parse("<html/>".toByteArray()) }.isFailure)
        assertTrue(runCatching { parse(byteArrayOf()) }.isFailure)
    }

    @Test fun brokenGzipFails() {
        assertTrue(runCatching { parse(byteArrayOf(0x1f, 0x8b.toByte(), 0)) }.isFailure)
    }

    @Test fun untrustedExternalEntitiesAreNotResolved() {
        val xml = """<!DOCTYPE tv [<!ENTITY external SYSTEM "file:///nonexistent">]>
            <tv><programme channel="a" start="20261111100000" stop="20261111110000"><title>&external;</title></programme></tv>"""
        val result = runCatching { parse(xml.toByteArray()) }
        assertTrue(result.isFailure || result.getOrThrow().second.none { it.title.contains("nonexistent") })
    }
}
