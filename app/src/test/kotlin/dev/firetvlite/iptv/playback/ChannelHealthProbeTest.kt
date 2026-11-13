package dev.firetvlite.iptv.playback

import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ChannelHealthProbeTest {
    private val media = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
    private val parse: (String, ByteArray) -> HealthPlaylist = { url, bytes ->
        val text = bytes.toString(Charsets.UTF_8)
        if (!text.startsWith("#EXTM3U")) throw IOException("invalid")
        if (text.contains("#EXT-X-STREAM-INF")) HealthPlaylist(variant = URI(url).resolve("first.m3u8").toString())
        else HealthPlaylist(segments = Regex("#EXTINF:").findAll(text).count())
    }

    @Test fun followsRedirectsAndOnlyFirstVariantWithoutDownloadingSegments() {
        MockWebServer().use { server ->
            val paths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    paths += request.path!!
                    assertEquals("GET", request.method)
                    assertEquals(listOf("ChannelUA"), request.headers.values("User-Agent"))
                    assertEquals("channel-reference", request.getHeader("Referer"))
                    assertEquals("close", request.getHeader("Connection"))
                    return when (request.path) {
                        "/start" -> MockResponse().setResponseCode(302).setHeader("Location", "/hls/master.m3u8")
                        "/hls/master.m3u8" -> MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nfirst.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000\nsecond.m3u8\n")
                        "/hls/first.m3u8" -> MockResponse().setBody(media)
                        else -> MockResponse().setResponseCode(500)
                    }
                }
            }
            server.start()
            val request = StreamRequest(server.url("/start").toString(), "SourceUA",
                headers = mapOf("user-agent" to "ChannelUA", "Referer" to "channel-reference"))
            val probe = ChannelHealthProbe(OkHttpClient(), parse = parse)
            assertNull(probe.check(request, {}, {}))
            assertEquals(listOf("/start", "/hls/master.m3u8", "/hls/first.m3u8"), paths)
        }
    }

    @Test fun requiresHttp200AndNonemptyParsedMediaAndAcceptsNonHlsPrefix() {
        MockWebServer().use { server ->
            server.start()
            val probe = ChannelHealthProbe(OkHttpClient(), parse = parse)
            val cases = listOf(
                MockResponse().setResponseCode(404) to "HTTP 404",
                MockResponse().setResponseCode(503) to "HTTP 503",
                MockResponse().setResponseCode(206).setBody("partial") to "HTTP 206",
                MockResponse().setBody("") to "empty response",
                MockResponse().setBody("#EXTM3U\n#EXT-X-TARGETDURATION:10\n") to "empty media playlist",
                MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("invalid") to "invalid playlist",
                MockResponse().setBody(media) to null,
                MockResponse().setHeader("Content-Type", "video/mp2t").setBody("x".repeat(8192)) to null,
            )
            for ((response, expected) in cases) {
                server.enqueue(response)
                assertEquals(expected, probe.check(StreamRequest(server.url("/stream").toString(), "TV"), {}, {}))
            }
        }
    }

    @Test fun oneDeadlineCoversMasterAndMediaAndClosesTimedOutCall() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nfirst.m3u8\n")
                .setHeadersDelay(100, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody(media).setHeadersDelay(500, TimeUnit.MILLISECONDS))
            val calls = mutableListOf<okhttp3.Call>()
            var cleared = false
            val probe = ChannelHealthProbe(OkHttpClient(), timeoutMs = 300, parse = parse)
            val start = System.nanoTime()
            assertEquals("no response 15s", probe.check(StreamRequest(server.url("/master.m3u8").toString(), "TV"),
                { if (it == null) cleared = true else calls += it }, {}))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertEquals(2, server.requestCount)
            assertTrue(calls.all { it.isCanceled() })
            assertTrue(cleared)
        }
    }
}
