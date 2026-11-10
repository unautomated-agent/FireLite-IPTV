package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupUrlBuilderTest {

    // 2024-03-05 07:08:09 UTC
    private val start = 1_709_622_489L
    private val now = start + 3_600L

    private fun channel(
        url: String = "http://host/live/1.m3u8",
        type: String? = null,
        source: String? = null,
        days: Int = 3,
    ) = ChannelEntity(
        sourceId = 1,
        position = 0,
        name = "One",
        groupName = "",
        streamUrl = url,
        logoUrl = null,
        tvgId = null,
        catchupType = type,
        catchupSource = source,
        catchupDays = days,
    )

    @Test
    fun shiftAppendsUtcAndNow() {
        val url = CatchupUrlBuilder.build(channel(type = "shift"), start, now, 3_600)

        assertEquals("http://host/live/1.m3u8?utc=$start&lutc=$now", url)
    }

    @Test
    fun shiftUsesAmpersandWhenTheUrlHasAQuery() {
        val url = CatchupUrlBuilder.build(channel(url = "http://host/1?token=x", type = "shift"), start, now, 60)

        assertEquals("http://host/1?token=x&utc=$start&lutc=$now", url)
    }

    @Test
    fun appendAddsTheExpandedSourceToTheStream() {
        val url = CatchupUrlBuilder.build(
            channel(type = "append", source = "?start={utc}&end={utcend}"),
            start,
            now,
            3_600,
        )

        assertEquals("http://host/live/1.m3u8?start=$start&end=${start + 3_600}", url)
    }

    @Test
    fun defaultUsesTheSourceAsACompleteAddress() {
        val url = CatchupUrlBuilder.build(
            channel(type = "default", source = "http://archive/\${start}/\${duration}"),
            start,
            now,
            1_800,
        )

        assertEquals("http://archive/$start/1800", url)
    }

    @Test
    fun defaultWithARelativeSourceIsAppended() {
        val url = CatchupUrlBuilder.build(channel(type = "default", source = "?t={offset}"), start, now, 60)

        assertEquals("http://host/live/1.m3u8?t=3600", url)
    }

    @Test
    fun expandsDatePartsInUtc() {
        assertEquals("2024-03-05 07:08:09", CatchupUrlBuilder.expand("{Y}-{m}-{d} {H}:{M}:{S}", start, now, 0))
    }

    @Test
    fun flussonicBuildsAnArchiveAddress() {
        val url = CatchupUrlBuilder.build(
            channel(url = "http://host/ch1/index.m3u8", type = "flussonic"),
            start,
            now,
            600,
        )

        assertEquals("http://host/ch1/archive-$start-600.m3u8", url)
    }

    @Test
    fun anUnknownTypeIsNotSupported() {
        val unknown = channel(type = "xc")

        assertFalse(CatchupUrlBuilder.isSupported(unknown))
        assertNull(CatchupUrlBuilder.build(unknown, start, now, 60))
    }

    @Test
    fun aChannelNeedsDaysAndAKnownType() {
        assertTrue(CatchupUrlBuilder.isSupported(channel(type = "shift", days = 2)))
        assertFalse(CatchupUrlBuilder.isSupported(channel(type = "shift", days = 0)))
        assertFalse(CatchupUrlBuilder.isSupported(channel(type = null, days = 2)))
    }
}
