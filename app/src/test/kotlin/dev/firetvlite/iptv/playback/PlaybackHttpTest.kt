package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import dev.firetvlite.iptv.data.playlist.M3uParser
import dev.firetvlite.iptv.data.playlist.ParsedChannel
import okhttp3.Request
import okhttp3.ConnectionPool
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class PlaybackHttpTest {
    @Test fun onlyPlaylistReadsHaveTheProviderWaitTimeoutAndConnectTimeoutIsUnchanged() {
        Fixture().use { fixture ->
            fixture.handler = { reply(it, 200) }
            val reads = mutableListOf<Int>()
            val connects = mutableListOf<Int>()
            val client = PlaybackHttp.client.newBuilder().addNetworkInterceptor { chain ->
                reads += chain.readTimeoutMillis()
                connects += chain.connectTimeoutMillis()
                chain.proceed(chain.request())
            }.build()
            for (type in listOf(C.DATA_TYPE_MANIFEST, C.DATA_TYPE_MEDIA, C.DATA_TYPE_DRM)) {
                PlaybackHttp.calls(fixture.base, type, client) {}.newCall(Request.Builder().url(fixture.base).build())
                    .execute().use { it.body!!.string() }
            }
            assertEquals(listOf(50_000, 20_000, 20_000), reads)
            assertEquals(listOf(15_000, 15_000, 15_000), connects)
            assertEquals(3, fixture.wire.size)
        }
    }

    @Test fun redirectedPlaylistCanReturnLate404OnTheSameCallWithSafeHopEvidence() {
        Fixture().use { fixture ->
            fixture.handler = { req -> when (req.path?.substringBefore('?')) {
                "/start" -> reply(req, 302).setHeader("Location", "/middle?channel=private")
                "/middle" -> reply(req, 302).setHeader("Location", "/final?token=secret&expires=123456789")
                else -> reply(req, 404).setHeadersDelay(150, TimeUnit.MILLISECONDS)
            } }
            val snapshots = mutableListOf<TransportSnapshot>()
            val root = "${fixture.base}/start"
            PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST, onTransport = { snapshots += it }) {}
                .newCall(Request.Builder().url(root).build()).execute().use { assertEquals(404, it.code) }
            val result = snapshots.last()
            assertEquals(404, result.status)
            assertTrue(result.afterSentMs!! >= 100)
            assertEquals(3, result.hop)
            assertEquals(1, result.hostIndex)
            assertEquals(listOf(RedirectHint(2, 1, true, false), RedirectHint(3, 1, true, true)), result.redirectHints)
            assertEquals(3, fixture.wire.size)
            val text = result.responseLine(1) + result.redirectsLine()
            for (secret in listOf("private", "secret", "123456789", fixture.base)) assertFalse(text.contains(secret))
        }
    }

    private class Fixture(tls: HandshakeCertificates? = null) : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val wire = CopyOnWriteArrayList<RecordedRequest>()
        var handler: (RecordedRequest) -> MockResponse = { reply(it, 404) }
        val server = MockWebServer().apply {
            if (tls != null) useHttps(tls.sslSocketFactory(), false)
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += "${request.method} ${request.path}"
                    wire += request
                    return handler(request)
                }
            }
            start(InetAddress.getByName("127.0.0.1"), 0)
        }
        val base = "${if (tls == null) "http" else "https"}://localhost:${server.port}"
        override fun close() = server.shutdown()
    }

    @Test fun crossHostRedirectRetainsTokenAndFinalUrlForRelativePlaylistsAndSegments() {
        Fixture().use { server ->
            server.handler = { exchange ->
                when (exchange.requestUrl!!.encodedPath) {
                    "/start.m3u8" -> redirect(exchange, "${server.base}/hop.m3u8")
                    "/hop.m3u8" -> redirect(exchange, "${server.base}/token%2Fdir/master.m3u8?token=fixture-secret", 307)
                    "/token%2Fdir/master.m3u8" -> reply(exchange, 200, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100\nchild/index.m3u8\n")
                    "/token%2Fdir/child/index.m3u8" -> reply(exchange, 200, "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\npart.ts\n#EXT-X-ENDLIST\n")
                    "/token%2Fdir/child/part.ts" -> reply(exchange, 200, "segment")
                    else -> reply(exchange, 404)
                }
            }
            val root = server.base.replace("localhost", "127.0.0.1") + "/start.m3u8"
            val evidence = mutableListOf<HttpRequestEvidence>()
            val calls = PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST) { evidence += it }
            val final = calls.newCall(Request.Builder().url(root).header("User-Agent", "Fixture UA")
                .header("Referer", "https://fixture.test/watch?secret=hidden").build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("${server.base}/token%2Fdir/master.m3u8?token=fixture-secret", response.request.url.toString())
                URI(response.request.url.toString()).resolve(response.body!!.string().lineSequence().first { !it.startsWith('#') }).toString()
            }
            val segment = calls.newCall(Request.Builder().url(final).build()).execute().use { response ->
                assertEquals(200, response.code)
                URI(response.request.url.toString()).resolve(response.body!!.string().lineSequence().first { !it.startsWith('#') }).toString()
            }
            PlaybackHttp.calls(root, C.DATA_TYPE_MEDIA) { evidence += it }
                .newCall(Request.Builder().url(segment).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("master/initial playlist", "media playlist", "segment"), evidence.map { it.classification })
            assertEquals(2, evidence.first().hosts)
            assertEquals(2, evidence.first().redirects)
            assertTrue(evidence.first().headers.any { it.equals("User-Agent", true) })
            assertTrue(evidence.first().headers.any { it.equals("Referer", true) })
            val safe = evidence.joinToString { it.lines().joinToString("\n") }
            listOf("fixture-secret", "token%2Fdir", "Fixture UA", "secret=hidden").forEach { assertFalse(safe.contains(it)) }
            assertEquals(5, server.requests.size)
            assertTrue(server.requests.all { it.startsWith("GET ") })
        }
    }

    @Test fun crossProtocolRedirectsAreFollowedBothDirectionsWithoutExtraRequests() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).addTrustedCertificate(certificate.certificate).build()
        val transport = PlaybackHttp.client.newBuilder().sslSocketFactory(tls.sslSocketFactory(), tls.trustManager).build()
        val snapshots = CopyOnWriteArrayList<TransportSnapshot>()
        Fixture(tls).use { secure -> Fixture().use { plain ->
            secure.handler = { exchange ->
                if (exchange.requestUrl!!.encodedPath == "/down.m3u8") redirect(exchange, "${plain.base}/final.m3u8")
                else reply(exchange, 200, "#EXTM3U")
            }
            plain.handler = { exchange ->
                if (exchange.requestUrl!!.encodedPath == "/up.m3u8") redirect(exchange, "${secure.base}/final.m3u8")
                else reply(exchange, 200, "#EXTM3U")
            }
            for (root in listOf("${plain.base}/up.m3u8", "${secure.base}/down.m3u8")) {
                var evidence: HttpRequestEvidence? = null
                PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST, transport, onTransport = { snapshots += it }) { evidence = it }
                    .newCall(Request.Builder().url(root).build()).execute().use { assertEquals(200, it.code) }
                assertEquals(1, evidence!!.redirects)
                assertEquals(1, evidence!!.hosts)
            }
            assertEquals(4, plain.requests.size + secure.requests.size)
            assertTrue(snapshots.any { it.tlsMs != null })
            assertTrue(snapshots.any { it.protocol == "http/1.1" && it.redirects == listOf(302) })
        } }
    }

    @Test fun standardPerChannelHeadersFixAHeaderGated404AndUrlParametersAreUnchanged() {
        Fixture().use { fixture ->
            fixture.handler = { exchange ->
                reply(exchange, if (exchange.getHeader("User-Agent") == "Fixture Player/1" &&
                    exchange.getHeader("Referer") == "https://fixture.test/watch") 200 else 404)
            }
            val root = "${fixture.base}/live%2Fchannel.ts?token=a%2Bb&x=1&x=2&tail="
            val calls = PlaybackHttp.calls(root, C.DATA_TYPE_MEDIA) {}
            calls.newCall(Request.Builder().url(root).header("User-Agent", "FireLite/2.0").build())
                .execute().use { assertEquals(404, it.code) }
            var parsed: ParsedChannel? = null
            M3uParser.parse(("#EXTINF:-1,Test\n#EXTVLCOPT:http-user-agent=Fixture Player/1\n" +
                "#EXTVLCOPT:http-referrer=https://fixture.test/watch\n$root").reader(), onChannel = { parsed = it })
            val request = Request.Builder().url(parsed!!.url)
            parsed!!.headers.forEach { (name, value) -> request.header(name, value) }
            calls.newCall(request.build()).execute().use { assertEquals(200, it.code) }
            assertEquals(listOf("GET /live%2Fchannel.ts?token=a%2Bb&x=1&x=2&tail=",
                "GET /live%2Fchannel.ts?token=a%2Bb&x=1&x=2&tail="), fixture.requests)
        }
    }

    @Test fun mediaPlaylistAndSegmentHttpFailuresAndRetryUrlAreDistinguishable() {
        Fixture().use { fixture ->
            for ((type, path, expected) in listOf(Triple(C.DATA_TYPE_MANIFEST, "/child.m3u8", "media playlist"),
                Triple(C.DATA_TYPE_MEDIA, "/segment.ts", "segment"))) {
                var last: HttpRequestEvidence? = null
                val calls = PlaybackHttp.calls("${fixture.base}/master.m3u8", type) { last = it }
                repeat(2) { calls.newCall(Request.Builder().url(fixture.base + path).build()).execute().use { assertEquals(404, it.code) } }
                assertEquals(expected, last!!.classification)
                assertEquals(0, last!!.redirects)
                assertEquals(last!!.originalId, last!!.finalId)
            }
            assertEquals(fixture.requests[0], fixture.requests[1])
            assertEquals(fixture.requests[2], fixture.requests[3])
        }
    }

    @Test fun redirectLoopsFailWithABoundedNumberOfGetRequests() {
        Fixture().use { fixture ->
            fixture.handler = { redirect(it, "${fixture.base}/loop") }
            val root = "${fixture.base}/loop"
            assertThrows(IOException::class.java) {
                PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST) {}.newCall(Request.Builder().url(root).build()).execute().close()
            }
            assertTrue(fixture.requests.size <= 21)
            assertTrue(fixture.requests.all { it.startsWith("GET ") })
        }
    }

    @Test fun http10RedirectConnectionsCanCloseWithoutConnectionHeader() {
        Fixture().use { fixture ->
            fixture.handler = { request ->
                when (request.path) {
                    "/entry" -> MockResponse().setStatus("HTTP/1.0 302 Found").setHeader("Location", "${fixture.base}/hop")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    "/hop" -> MockResponse().setStatus("HTTP/1.0 307 Temporary Redirect").setHeader("Location", "${fixture.base}/final")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END)
                    else -> reply(request, 200)
                }
            }
            val root = "${fixture.base}/entry"
            PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST) {}.newCall(Request.Builder().url(root).build()).execute()
                .use { assertEquals(200, it.code) }
            assertEquals(listOf("GET /entry", "GET /hop", "GET /final"), fixture.requests)
        }
    }

    @Test fun providerRegistrationCanOutliveAClosedPreviousResponseAndReturn404DuringGrace() {
        Fixture().use { fixture ->
            val clock = java.util.concurrent.atomic.AtomicLong(0)
            var registeredUntil = 0L
            fixture.handler = { exchange ->
                if (exchange.requestUrl!!.encodedPath == "/previous") {
                    registeredUntil = clock.get() + 800
                    reply(exchange, 200)
                } else reply(exchange, if (clock.get() < registeredUntil) 404 else 200)
            }
            val root = "${fixture.base}/next.m3u8"
            val calls = PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST) {}
            calls.newCall(Request.Builder().url("${fixture.base}/previous").build()).execute().use { assertEquals(200, it.code) }
            clock.set(400)
            calls.newCall(Request.Builder().url(root).build()).execute().use { assertEquals(404, it.code) }
            clock.set(900)
            calls.newCall(Request.Builder().url(root).build()).execute().use { assertEquals(200, it.code) }
            assertEquals(3, fixture.requests.size)
        }
    }

    @Test fun exactlyOneUserAgentReachesTheWireWithChannelThenSourceThenDefaultPrecedence() {
        Fixture().use { fixture ->
            fixture.handler = { reply(it, 200) }
            val root = "${fixture.base}/master.m3u8"
            for ((headers, source, expected) in listOf(
                Triple(mapOf("uSeR-aGeNt" to "Channel player"), "Source player", "Channel player"),
                Triple(emptyMap(), "Source player", "Source player"),
                Triple(emptyMap(), "", "FireLite/2.0"))) {
                val snapshots = mutableListOf<TransportSnapshot>()
                PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST, channelHeaders = headers, userAgent = source,
                    onTransport = { snapshots += it }) {}.newCall(Request.Builder().url(root)
                    .addHeader("User-Agent", "old channel value").addHeader("User-Agent", "old source value").build())
                    .execute().use { assertEquals(200, it.code); it.body!!.string() }
                assertEquals(listOf(expected), fixture.wire.last().headers.values("User-Agent"))
                assertEquals(1, snapshots.last().uaFields)
            }
        }
    }

    @Test fun sessionEvictionUsesAFreshSocketButRequestsWithinTheSessionReuseTheirSocket() {
        Fixture().use { fixture ->
            fixture.handler = { reply(it, 200) }
            val client = PlaybackHttp.client.newBuilder().connectionPool(ConnectionPool()).build()
            val root = "${fixture.base}/master.m3u8"
            val snapshots = mutableListOf<TransportSnapshot>()
            val calls = PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST, client, onTransport = { snapshots += it }) {}
            fun get() = calls.newCall(Request.Builder().url(root).build()).execute().use { it.body!!.string() }
            get()
            assertEquals(false, snapshots.last().reused)
            get()
            assertEquals(true, snapshots.last().reused)
            PlaybackHttp.evictIdle(client)
            get()
            assertEquals(false, snapshots.last().reused)
            assertEquals(listOf(0, 1, 0), fixture.wire.map { it.sequenceNumber })
            PlaybackHttp.evictIdle(client)
        }
    }

    @Test fun idleEvictionDoesNotCancelAnActiveResponseOrIssueAnyRequests() {
        Fixture().use { fixture ->
            fixture.handler = { reply(it, 200, "active response") }
            val client = PlaybackHttp.client.newBuilder().connectionPool(ConnectionPool()).build()
            PlaybackHttp.evictIdle(client)
            val root = "${fixture.base}/segment.ts"
            PlaybackHttp.calls(root, C.DATA_TYPE_MEDIA, client) {}.newCall(Request.Builder().url(root).build())
                .execute().use { response ->
                    PlaybackHttp.evictIdle(client)
                    assertEquals("active response", response.body!!.string())
                }
            assertEquals(1, fixture.wire.size)
            PlaybackHttp.evictIdle(client)
        }
    }

    @Test fun aResponseHeaderStallRecordsWaitingThenFailureWithoutIssuingAnotherRequest() {
        Fixture().use { fixture ->
            fixture.handler = { reply(it, 200).setHeadersDelay(500, TimeUnit.MILLISECONDS) }
            val client = PlaybackHttp.client.newBuilder().addInterceptor { chain ->
                chain.withReadTimeout(100, TimeUnit.MILLISECONDS).proceed(chain.request())
            }.build()
            val root = "${fixture.base}/master.m3u8?token=private"
            val snapshots = CopyOnWriteArrayList<TransportSnapshot>()
            assertThrows(IOException::class.java) {
                PlaybackHttp.calls(root, C.DATA_TYPE_MANIFEST, client, onTransport = { snapshots += it }) {}
                    .newCall(Request.Builder().url(root).build()).execute().close()
            }
            assertTrue(snapshots.any { it.phase == TransportPhase.Waiting && it.sentMs != null && it.headersMs == null })
            assertEquals(TransportPhase.Failed, snapshots.last().phase)
            assertEquals(1, fixture.wire.size)
            assertFalse(snapshots.joinToString { it.line(1) }.contains("private"))
            PlaybackHttp.evictIdle(client)
        }
    }

    companion object {
        private fun reply(exchange: RecordedRequest, status: Int, body: String = "fixture"): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody(body)
        private fun redirect(exchange: RecordedRequest, location: String, status: Int = 302): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Location", location)
    }
}
