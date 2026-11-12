package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.ui.components.clockTime
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelRowPresentationTest {
    @Test fun nextProgrammeUsesPolishCopyAndWarsawTime() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string")
        val template = (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "channel_row_next_programme" }.textContent
        assertEquals("Następnie 21:15 Film", String.format(Locale.ROOT, template,
            clockTime(Instant.parse("2026-11-12T20:15:00Z").toEpochMilli()), "Film"))
    }
}
