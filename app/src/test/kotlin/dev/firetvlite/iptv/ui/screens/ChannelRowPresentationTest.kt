package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.millisUntilNextMinute
import java.io.File
import java.time.Instant
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelRowPresentationTest {
    @Test fun homeMinuteClockWakeupAdvancesTheGuideQueryKeyWithoutNavigation() {
        val firstFrame = Instant.parse("2026-11-12T20:15:23.456Z").toEpochMilli()
        val wakeup = firstFrame + millisUntilNextMinute(firstFrame)
        assertEquals(Instant.parse("2026-11-12T20:16:00Z").toEpochMilli(), wakeup)
        assertEquals(channelGuideQueryMinute(firstFrame) + 1, channelGuideQueryMinute(wakeup))
        assertEquals(channelGuideQueryMinute(wakeup) + 1,
            channelGuideQueryMinute(wakeup + millisUntilNextMinute(wakeup)))
    }

    @Test fun secondTicksKeepGuideQueryKeyUntilTheMinuteChanges() {
        val start = Instant.parse("2026-11-12T20:15:00Z").toEpochMilli()
        val key = channelGuideQueryMinute(start)
        for (second in 0 until 60) assertEquals(key, channelGuideQueryMinute(start + second * 1_000))
        assertEquals(key, channelGuideQueryMinute(start + 59_999))
        assertEquals(key + 1, channelGuideQueryMinute(start + 60_000))
        assertEquals(-1L, channelGuideQueryMinute(-1))
    }

    @Test fun nextProgrammeUsesPolishCopyAndWarsawTime() {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string")
        val template = (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "channel_row_next_programme" }.textContent
        assertEquals("Następnie 21:15 Film", String.format(Locale.ROOT, template,
            clockTime(Instant.parse("2026-11-12T20:15:00Z").toEpochMilli()), "Film"))
    }
}
