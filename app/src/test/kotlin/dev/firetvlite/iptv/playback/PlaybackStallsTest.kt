package dev.firetvlite.iptv.playback

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import dev.firetvlite.iptv.data.library.DiagnosticsReport

class PlaybackStallsTest {
    @Test fun largeDeadRegistryCannotCrowdOutRecentStalls() {
        val dead = ChannelHealthState((1..100).map {
            DeadChannel("$it", "Kanał Żółć $it".repeat(10), 1, 1, 1, "HTTP 404", "playback")
        })
        val stalls = (5 downTo 1).map {
            PlaybackStall(it.toLong(), "Stall TV $it", it * 4_000L, "decoder/app", "buffered media not rendering")
        }.joinToString("\n") { it.line(true) }
        val report = dead.qrSummary() + "\n" + stalls + "\n" + "Transport pending: waiting response\n".repeat(300)
        val payload = DiagnosticsReport.payload(report)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DiagnosticsReport.QR_BYTES)
        assertTrue(payload, payload.contains("Stall TV 5 20s decoder/app"))
        assertTrue(payload, payload.contains("Stall TV 4 16s decoder/app"))
        assertTrue(payload, payload.contains("Zawieszenie: +3 więcej"))
        assertTrue(payload, payload.contains("Martwe (100):"))
        assertTrue(payload, payload.contains("więcej"))
        assertFalse(payload.contains('\uFFFD'))
        assertEquals(payload, DiagnosticsReport.payload(payload))
    }

    @get:Rule val folder = TemporaryFolder()
    private fun sample(now: Long = 1_000) = StallSample(now, 1_700_000_000_000 + now, "TV Test",
        active = true, buffering = true, ready = false, frames = 5, positionMs = 1_000,
        bufferedMs = 0, byteAgeMs = 4_000)

    private fun detected(value: StallSample): PlaybackStall {
        val detector = StallDetector()
        assertNull(detector.sample(value))
        return detector.sample(value.copy(nowMs = value.nowMs + 3_500, wallMs = value.wallMs + 3_500))!!
    }

    @Test fun rebufferClassificationUsesBytesHttpAndSlowLoads() {
        assertEquals("provider", detected(sample()).kind)
        assertTrue(detected(sample()).evidence.contains("no media bytes"))
        val http = detected(sample().copy(bufferedMs = 2_000, byteAgeMs = 0, httpError = 503))
        assertEquals("provider", http.kind)
        assertTrue(http.evidence.contains("HTTP 503"))
        val slow = detected(sample().copy(bufferedMs = 2_000, byteAgeMs = 500, loadMs = 7_000, bandwidth = 64_000))
        assertEquals("provider", slow.kind)
        assertTrue(slow.evidence.contains("bps=64000"))
    }

    @Test fun frozenFramesDespiteMovingAudioAndBufferedDataIndicateDecoder() {
        val detector = StallDetector()
        val value = sample().copy(buffering = false, ready = true, bufferedMs = 9_000)
        assertNull(detector.sample(value))
        val freeze = detector.sample(value.copy(nowMs = 4_500, positionMs = 4_500, dropped = 30))!!
        assertEquals("decoder/app", freeze.kind)
        assertTrue(freeze.evidence.contains("dropped=30"))
        assertEquals("decoder/app", detected(sample().copy(decoderError = true)).kind)
        assertEquals("decoder/app", detected(sample().copy(bufferedMs = 9_000)).kind)
        assertEquals("unknown", detected(sample().copy(byteAgeMs = 0, bufferedMs = 2_000)).kind)
    }

    @Test fun advancingPlaybackPauseStartupAndShortRebuffersAreSilent() {
        val detector = StallDetector()
        assertNull(detector.sample(sample().copy(active = false)))
        assertNull(detector.sample(sample(5_000).copy(active = false)))
        assertNull(detector.sample(sample(6_000)))
        assertNull(detector.sample(sample(8_000)))
        assertNull(detector.sample(sample(8_500).copy(active = false)))
        for (now in 9_000L..20_000L step 500) {
            assertNull(detector.sample(sample(now).copy(buffering = false, ready = true,
                frames = now.toInt(), positionMs = now)))
        }
    }

    @Test fun reportsOnceThenUpdatesDurationAndRecoversWithoutActions() {
        val detector = StallDetector()
        detector.sample(sample())
        val first = detector.sample(sample(4_000))!!
        assertNull(detector.sample(sample(4_500)))
        val ongoing = detector.sample(sample(9_000))!!
        assertEquals(first.time, ongoing.time)
        assertEquals(8_000L, ongoing.duration)
        val ended = detector.sample(sample(10_000).copy(buffering = false, ready = true, frames = 99, positionMs = 99_000))!!
        assertEquals(9_000L, ended.duration)
        assertNull(detector.sample(sample(11_000).copy(buffering = false, ready = true, frames = 100, positionMs = 100_000)))
    }

    @Test fun mainThreadStallCorrelatesEvenWhenRendererContinuedOffMain() {
        val detector = StallDetector()
        detector.sample(sample().copy(buffering = false, ready = true))
        val lag = detector.sample(sample(10_000).copy(buffering = false, ready = true,
            frames = 100, positionMs = 10_000, jankMs = 9_000))!!
        assertEquals("decoder/app", lag.kind)
        assertTrue(lag.evidence.contains("main-thread=9000ms"))
        assertNull(detector.sample(sample(10_500).copy(buffering = false, ready = true,
            frames = 110, positionMs = 10_500, jankMs = 9_000)))
    }

    @Test fun historyPersistsCapsUpdatesAndKeepsQrBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(folder.root, "stalls.json")
        try {
            val history = StallHistory(file, scope)
            history.flush()
            assertTrue(history.state.value.isEmpty())
            for (index in 1..7) history.record(PlaybackStall(index.toLong(), "TV $index", 4_000, "provider", "HTTP 404"))
            history.record(PlaybackStall(7, "TV 7", 12_000, "provider", "HTTP 404"))
            history.flush()
            assertEquals(5, history.state.value.size)
            assertEquals(12_000L, history.state.value.first().duration)
            val restored = StallHistory(file, scope)
            restored.flush()
            assertEquals(history.state.value, restored.state.value)
            val lines = restored.state.value.joinToString("\n") { it.line(true) }
            val dead = ChannelHealthState((1..30).map { DeadChannel("$it", "TV$it", 1, 1, 1, "HTTP 404", "playback") })
            val payload = DiagnosticsReport.payload(dead.qrSummary() + "\n" + lines + "\n" + "Transport pending: waiting response\n".repeat(300))
            assertTrue(payload.toByteArray().size <= 1800)
            for (index in 3..7) assertTrue(payload, payload.contains("TV $index"))
            for (index in 1..30) assertTrue(payload, payload.contains("TV$index 404"))
            file.writeText("broken")
            val broken = StallHistory(file, scope)
            broken.flush()
            assertTrue(broken.state.value.isEmpty())
        } finally { scope.cancel() }
    }
}
