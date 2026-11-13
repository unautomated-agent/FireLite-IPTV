package dev.firetvlite.iptv.playback

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class TransportTraceTest {
    private val request = Request.Builder().url("https://private.example/123456789?token=hidden").build()
    private val call = OkHttpClient().newCall(request)
    private val address = InetSocketAddress.createUnresolved("private.example", 443)

    @Test fun pendingDnsConnectTlsAndResponseHaveIndependentElapsedTimesWithoutSensitiveValues() {
        var time = 0L
        val snapshots = mutableListOf<TransportSnapshot>()
        val trace = TransportTrace(setOf("User-Agent", "Referer", "Private-123456789"), { snapshots += it }, { time })
        trace.callStart(call)
        assertEquals("pending: queued 0ms", snapshots.last().pending(time))
        trace.dnsStart(call, "private.example")
        time = 12
        trace.dnsEnd(call, "private.example", listOf(InetAddress.getLoopbackAddress()))
        trace.connectStart(call, address, Proxy.NO_PROXY)
        time = 9500
        assertEquals("pending: connecting 9488ms", snapshots.last().pending(time))
        trace.secureConnectStart(call)
        time += 10
        trace.secureConnectEnd(call, null)
        trace.connectEnd(call, address, Proxy.NO_PROXY, Protocol.HTTP_2)
        trace.requestHeadersStart(call)
        trace.requestHeadersEnd(call, request.newBuilder().header("User-Agent", "private UA").build())
        time += 25
        trace.responseHeadersStart(call)
        assertEquals("pending: waiting response 25ms", snapshots.last().pending(time))
        trace.responseHeadersEnd(call, Response.Builder().request(request).protocol(Protocol.HTTP_2)
            .code(200).message("secret message").build())
        trace.callEnd(call)
        val snapshot = snapshots.last()
        assertEquals(12L, snapshot.dnsMs)
        assertEquals(9498L, snapshot.connectMs)
        assertEquals(10L, snapshot.tlsMs)
        assertEquals(9510L, snapshot.sentMs)
        assertEquals(9535L, snapshot.headersMs)
        assertEquals("h2", snapshot.protocol)
        assertEquals(1, snapshot.uaFields)
        assertEquals(listOf("User-Agent", "Referer"), snapshot.headerNames)
        val text = snapshot.line(1) + snapshot.headersLine()
        for (secret in listOf("private", "hidden", "123456789", "secret", "https://")) assertFalse(text.contains(secret))
    }

    @Test fun redirectsAreBoundedAndFailuresDoNotRetainExceptionsOrCreateRetries() {
        var time = 0L
        val snapshots = mutableListOf<TransportSnapshot>()
        val trace = TransportTrace(emptySet(), { snapshots += it }, { time })
        trace.callStart(call)
        assertFalse(snapshots.last().channelHeaders)
        assertEquals(emptyList<String>(), snapshots.last().headerNames)
        repeat(9) { trace.responseHeadersEnd(call, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(302).header("Location", "https://secret.example/123456789").message("private").build()) }
        assertEquals(listOf(302, 302, 302, 302), snapshots.last().redirects)
        time = 9_999_999
        trace.connectFailed(call, address, Proxy.NO_PROXY, null, IOException("secret password=private"))
        trace.callFailed(call, IOException("https://secret.example/123456789"))
        val snapshot = snapshots.last()
        assertEquals(TransportPhase.Failed, snapshot.phase)
        assertFalse(snapshot.line(9_999_999).contains("secret"))
        assertFalse(Regex("\\d{7,}").containsMatchIn(snapshot.line(9_999_999) + snapshot.pending(time)))
    }
}
