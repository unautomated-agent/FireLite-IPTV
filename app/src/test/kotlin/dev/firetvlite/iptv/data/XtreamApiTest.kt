package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.db.XtreamChannelEntity
import dev.firetvlite.iptv.data.playlist.XtreamAddress
import dev.firetvlite.iptv.data.playlist.XtreamApi
import dev.firetvlite.iptv.data.playlist.XtreamJson
import dev.firetvlite.iptv.data.playlist.XtreamMetadataReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class XtreamApiTest {
    private val address = XtreamAddress("https://provider:8443", "test user", "test+password")
    private val source = SourceEntity(id = 1, name = "Provider", url = address.origin + "/get.php?username=test+user&password=test%2Bpassword", userAgent = "Test")
    private val live = """[{"stream_id":"12","tv_archive":1,"tv_archive_duration":"7","epg_channel_id":"TVN.PL"}]"""
    private val info = """{"user_info":{"auth":"1"},"server_info":{"timezone":"Europe/Warsaw","timestamp_now":123}}"""
    private fun channel() = ChannelEntity(sourceId = 1, position = 0, name = "TVN", groupName = "",
        streamUrl = "https://cdn/live/test%20user/test%2Bpassword/12.m3u8", logoUrl = null, tvgId = null,
        catchupType = null, catchupSource = null, catchupDays = 0)

    private class FakeReader(var live: String, var info: String) : XtreamMetadataReader {
        val requests = mutableListOf<String>()
        var liveFailure: Exception? = null
        var infoFailure: Exception? = null
        override fun <T> read(url: String, agent: String, parse: (Reader) -> T): T {
            requests.add(url)
            val isLive = url.contains("action=get_live_streams")
            (if (isLive) liveFailure else infoFailure)?.let { throw it }
            return StringReader(if (isLive) live else info).use(parse)
        }
    }

    @Test fun enrichesUnmarkedM3uByStreamIdAndOnlyCallsMetadataEndpoints() {
        val reader = FakeReader(live, info)
        val result = XtreamApi(reader).refresh(source, address, emptyList())
        val row = result.enrich(listOf(channel()), address).single()
        assertEquals("xc", row.catchupType)
        assertEquals(7, row.catchupDays)
        assertEquals("TVN.PL", row.tvgId)
        assertEquals("Europe/Warsaw", row.catchupTimezone)
        assertEquals(2, reader.requests.size)
        assertTrue(reader.requests.all { it.startsWith("https://provider:8443/player_api.php?") })
        assertTrue(reader.requests[0].contains("username=test+user&password=test%2Bpassword"))
        assertEquals(channel(), result.enrich(listOf(channel().copy(streamUrl = "https://cdn/live/other/account/12.ts")), address)
            .single().copy(streamUrl = channel().streamUrl))
    }

    @Test fun workingM3uWinsTiesAndLongerRetentionWhileApiCanEnableMore() {
        val result = XtreamApi(FakeReader(live, info)).refresh(source, address, emptyList())
        for (days in listOf(7, 10)) {
            val explicit = channel().copy(catchupDays = days, catchupType = "default", catchupSource = "?start={utc}", tvgId = "M3U-ID")
            val merged = result.enrich(listOf(explicit), address).single()
            assertEquals(days, merged.catchupDays)
            assertEquals("default", merged.catchupType)
            assertEquals("M3U-ID", merged.tvgId)
        }
        val merged = result.enrich(listOf(channel().copy(catchupDays = 3, catchupType = "append", catchupSource = "?utc={utc}")), address).single()
        assertEquals(7, merged.catchupDays)
        assertEquals("xc", merged.catchupType)
        assertNull(merged.catchupSource)
    }

    @Test fun failuresKeepPreviousSameAccountMetadataAndZoneButNotAnotherAccount() {
        val old = listOf(XtreamChannelEntity(1, "12", true, 3, "old-id"))
        val saved = source.copy(xtreamAccount = address.accountKey(), xtreamTimezone = "Europe/Warsaw")
        val reader = FakeReader(live, info).apply { liveFailure = IOException("HTTP 503"); infoFailure = IOException("timeout") }
        val cached = XtreamApi(reader).refresh(saved, address, old)
        assertFalse(cached.refreshed)
        assertEquals(old, cached.rows)
        assertEquals("Europe/Warsaw", cached.timezone)
        assertEquals("old-id", cached.enrich(listOf(channel()), address).single().tvgId)
        assertTrue(cached.status.contains("HTTP 503"))
        val different = XtreamApi(reader).refresh(source, address, old)
        assertTrue(different.rows.isEmpty())
        assertEquals("UTC", different.timezone)
    }

    @Test fun successfulEmptyListClearsCacheAndMissingZoneFallsBackToUtc() {
        val old = listOf(XtreamChannelEntity(1, "12", true, 3, "old-id"))
        val result = XtreamApi(FakeReader("[]", """{"server_info":{}}"""))
            .refresh(source.copy(xtreamAccount = address.accountKey(), xtreamTimezone = "Europe/Warsaw"), address, old)
        assertTrue(result.refreshed)
        assertTrue(result.rows.isEmpty())
        assertEquals("UTC", result.timezone)
        assertEquals(0, result.enrich(listOf(channel()), address).single().catchupDays)
        assertEquals(4, result.enrich(listOf(channel().copy(catchupType = "shift", catchupDays = 4)), address).single().catchupDays)
    }

    @Test fun malformedLiveJsonRetainsCacheWhileServerInfoCanStillUpdate() {
        val old = listOf(XtreamChannelEntity(1, "12", true, 3, "old-id"))
        for (broken in listOf("[{", "{}", "<html>denied</html>", "[{}] trailing")) {
            val result = XtreamApi(FakeReader(broken, info)).refresh(source.copy(xtreamAccount = address.accountKey()), address, old)
            assertFalse(result.refreshed)
            assertEquals(old, result.rows)
            assertEquals("Europe/Warsaw", result.timezone)
        }
        assertNull(XtreamJson.timezone(StringReader("""{"server_info":{"timezone":"Not/AZone"}}""")))
    }

    @Test fun parserHandlesStringsNumbersNullsUnknownNestedFieldsAndLargeLists() {
        val json = """[null,42,{}, {"stream_id":12.0,"tv_archive":"1","tv_archive_duration":5.0,"epg_channel_id":null,"unknown":{"nested":[1,2]}},
            {"stream_id":"13","tv_archive":false,"tv_archive_duration":"7","epg_channel_id":"null"},
            {"stream_id":"14","tv_archive":true,"tv_archive_duration":"3","epg_channel_id":123}]"""
        val rows = XtreamJson.channels(StringReader(json), 1)
        assertEquals(listOf("12", "13", "14"), rows.map { it.streamId })
        assertEquals(listOf(5, 0, 3), rows.map { it.days })
        assertEquals("123", rows.last().epgId)
        val large = (1..12000).joinToString(",", "[", "]") { """{"stream_id":$it,"tv_archive":"1","tv_archive_duration":"7"}""" }
        assertEquals(12000, XtreamJson.channels(StringReader(large), 1).size)
    }

    @Test fun cancellationIsNotConvertedToSuccessfulImportOrCachedFailure() {
        val reader = FakeReader(live, info).apply { liveFailure = CancellationException("cancelled") }
        try { XtreamApi(reader).refresh(source, address, emptyList()); fail("Cancellation swallowed") }
        catch (_: CancellationException) { assertEquals(1, reader.requests.size) }
    }
}
