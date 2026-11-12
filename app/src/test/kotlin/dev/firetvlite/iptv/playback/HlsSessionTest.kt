package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsSessionTest {
    @Test fun pendingTraceDistinguishesConnectionFromResponseAndDropsLateUpdatesOutsideTheBound() {
        var time = 0L
        val session = HlsSession(clock = { time })
        val id = session.begin("https://private.example/secret.m3u8", C.DATA_TYPE_MANIFEST)
        session.evicted()
        session.transport(id, TransportSnapshot(TransportPhase.Connecting, 0))
        time = 9587
        assertTrue(session.lines().contains("HLS request: master pending: connecting 9587ms m3u8"))
        assertTrue(session.lines().contains("Transport pool: idle evicted before close grace"))
        session.transport(id, TransportSnapshot(TransportPhase.Waiting, 9500, sentMs = 9500,
            protocol = "http/1.1", reused = false, uaFields = 1))
        assertTrue(session.lines().contains("HLS request: master pending: waiting response 87ms m3u8"))
        assertTrue(session.lines().contains("Transport pending: waiting response 87ms"))
        session.response(id, 200)
        session.end(id)
        assertFalse(session.lines().any { it.startsWith("Transport pending:") })
        repeat(12) { session.begin("https://private.example/$it.ts", C.DATA_TYPE_MEDIA) }
        session.transport(id, TransportSnapshot(TransportPhase.Failed, 0))
        assertFalse(session.lines().any { it.startsWith("Transport:") })
        assertEquals(12, session.lines().count { it.startsWith("HLS request:") })
    }

    @Test fun emptySessionHasNoRequestTraceOrExtraStartupBudget() {
        val session = HlsSession(clock = { 0 })
        assertFalse(session.lines().any { it.startsWith("HLS request:") })
        assertTrue(session.lines().contains("HLS master: variants=0; selected=pending"))
        assertEquals(10_000L, session.timeoutAt(10_000))
    }

    @Test fun traceKeepsOnlyTwelveRequestsAndNeverIncludesUrlsOrTokens() {
        var time = 0L
        val session = HlsSession(clock = { time })
        repeat(20) {
            val id = session.begin("https://provider/secret/123456789012/$it.ts?token=private", C.DATA_TYPE_MEDIA)
            time += 12
            session.response(id, if (it == 19) 404 else 200)
            session.end(id)
        }
        val trace = session.lines().filter { it.startsWith("HLS request:") }
        assertEquals(12, trace.size)
        assertEquals("HLS request: segment 404 12ms ts", trace.last())
        val text = session.lines().joinToString("\n")
        for (secret in listOf("https", "provider", "secret", "123456", "token", "private")) assertFalse(text.contains(secret))
    }

    @Test fun incompleteAndIoRequestsHaveBoundedDurationsAndAllowlistedExtensions() {
        var time = 0L
        val session = HlsSession(clock = { time })
        val id = session.begin("https://provider/123456789.accesscode", C.DATA_TYPE_MANIFEST)
        time = 9_999_999
        assertEquals("HLS request: master pending 999999ms -", session.lines().last())
        session.end(id)
        assertEquals("HLS request: master IO 999999ms -", session.lines().last())
        assertEquals("m3u8", HlsSession.extension("https://provider/a.M3U8?token=.private"))
        assertEquals("-", HlsSession.extension("not a URL"))
    }

    @Test fun lateTypedStatusUpdatesItsRequestWithoutReplacingNewerTraceEntries() {
        val session = HlsSession(clock = { 0 })
        val uri = "https://provider/audio.m3u8"
        val id = session.begin(uri, C.DATA_TYPE_MANIFEST)
        session.end(id)
        session.begin("https://provider/video.ts", C.DATA_TYPE_MEDIA)
        session.failedRequest(uri, 404)
        assertTrue(session.lines().contains("HLS request: master 404 0ms m3u8"))
        assertEquals("HLS request: segment pending 0ms ts", session.lines().last())
    }

    @Test fun aNewSessionRejectsLateLoadsFromThePreviousChannel() {
        val old = HlsSession(clock = { 0 })
        old.begin("https://provider/old.m3u8", C.DATA_TYPE_MANIFEST)
        val current = HlsSession(clock = { 0 })
        current.begin("https://provider/new.m3u8", C.DATA_TYPE_MANIFEST)
        assertTrue(old.owns("https://provider/old.m3u8"))
        assertFalse(current.owns("https://provider/old.m3u8"))
        assertTrue(current.owns("https://provider/new.m3u8"))
    }

    @Test fun onlyNewFailoverProgressExtendsStartupAndTheBudgetIsBounded() {
        var time = 0L
        val session = HlsSession(clock = { time })
        time = 8_000
        session.loaded("normal.ts")
        assertEquals(10_000L, session.timeoutAt(10_000))
        assertTrue(session.fallback("bad.m3u8"))
        assertEquals(18_000L, session.timeoutAt(10_000))
        time = 15_000
        assertFalse(session.fallback("bad.m3u8"))
        assertEquals(18_000L, session.timeoutAt(10_000))
        session.loaded("good.m3u8")
        assertEquals(25_000L, session.timeoutAt(10_000))
        time = 29_000
        session.loaded("good.m3u8")
        assertEquals(25_000L, session.timeoutAt(10_000))
        session.loaded("next.ts")
        assertEquals(30_000L, session.timeoutAt(10_000))
    }

    @Test fun onlyLiveSegmentsCanBeSkippedAndConsecutiveFailuresAreCapped() {
        val live = HlsSession(clock = { 0 })
        assertTrue(live.skip("one.ts"))
        assertFalse(live.skip("one.ts"))
        assertTrue(live.skip("two.ts"))
        assertTrue(live.skip("three.ts"))
        assertFalse(live.skip("four.ts"))
        live.loaded("good.ts")
        assertTrue(live.skip("four.ts"))
        assertFalse(HlsSession(archive = true, clock = { 0 }).skip("archive.ts"))
        assertEquals("key", live.kind("key", C.DATA_TYPE_DRM))
        assertEquals("init", live.kind("init", C.DATA_TYPE_MEDIA_INITIALIZATION))
    }
}
