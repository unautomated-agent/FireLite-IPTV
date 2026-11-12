package dev.firetvlite.iptv.playback

import com.google.zxing.BinaryBitmap
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.firetvlite.iptv.data.library.DiagnosticsReport
import dev.firetvlite.iptv.data.library.SourceDiagnostics
import dev.firetvlite.iptv.data.db.SourceEntity
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun record(error: Throwable? = null) = PlaybackDiagnosticRecord.text(
        "https://provider/play/hls/custom/account/private-path-token?api_key=private-query-key",
        "preview/archive", if (error == null) null else "ERROR_CODE_DECODING_FAILED", error,
        "A audio/ac3/ac-3 ch=6 bps=384000; V video/avc/avc1 ch=-1 bps=2000000",
        "audio/ac3; audio/mp4a-latm; video/avc", "c2.android.ac3.decoder", Instant.EPOCH)

    @Test fun errorsAreMaskedBeforePersistenceAndKeepCauseAndFormatDetails() {
        val error = IllegalStateException("https://user:password@provider/live/private-user/private-pass/12.ts",
            IllegalArgumentException("api_key=private-query-key"))
        val text = record(error)
        listOf("private-path-token", "private-query-key", "private-user", "private-pass", "user:password").forEach {
            assertFalse(text, text.contains(it))
        }
        listOf("1970-01-01", "preview/archive", "ERROR_CODE_DECODING_FAILED", "java.lang.IllegalStateException",
            "java.lang.IllegalArgumentException", "audio/ac3", "ch=6", "384000", "c2.android.ac3.decoder", "audio/mp4a-latm").forEach {
            assertTrue(text, text.contains(it))
        }
        val store = PlaybackDiagnosticsStore(temporary.root)
        store.write(true, text)
        assertEquals(text, File(temporary.root, "playback-error.txt").readText())
    }

    @Test fun emptyAndSuccessfulRecordsSurviveReopeningWithoutReplacingTheLastError() {
        val store = PlaybackDiagnosticsStore(temporary.root)
        assertTrue(store.summary().contains("Brak"))
        store.write(true, record(IllegalArgumentException("bad codec")))
        store.write(false, record())
        val reopened = PlaybackDiagnosticsStore(temporary.root).summary()
        assertTrue(reopened.contains("ERROR_CODE_DECODING_FAILED"))
        assertTrue(reopened.contains("OK"))
        assertTrue(reopened.contains("c2.android.ac3.decoder"))
    }

    @Test fun longUnicodeCausesAndQrStayBoundedWithoutBrokenSurrogates() {
        val error = IllegalArgumentException("Żółć 🎬 ".repeat(3000))
        val store = PlaybackDiagnosticsStore(temporary.root)
        store.write(true, record(error))
        store.write(false, record())
        val summary = store.summary()
        val payload = DiagnosticsReport.payload(DiagnosticsReport.text(null, null, SourceDiagnostics(), "🎬".repeat(3000), summary))
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DiagnosticsReport.QR_BYTES)
        assertEquals(payload, String(payload.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(payload.contains("Ostatni błąd odtwarzania:"))
        assertTrue(payload.contains("Ostatnie poprawne odtwarzanie:"))
        assertTrue(File(temporary.root, "playback-error.txt").length() < 8000)
    }

    @Test fun cyclicCauseChainsAreBounded() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        val text = record(first)
        assertTrue(text.contains("first"))
        assertTrue(text.length < 2000)
    }

    @Test fun startupStateHttpAndTrackDetailsSurvivePersistenceAndQrSummary() {
        val store = PlaybackDiagnosticsStore(temporary.root)
        val text = PlaybackDiagnosticRecord.text("https://provider/play/hls-custom-private-code.m3u8", "live",
            "START_TIMEOUT", null, "video/hevc hvc1", "video/hevc [nieobsługiwany]", null, Instant.EPOCH,
            listOf("Channel: TVP Seriale", "Player: BUFFERING; pos=0; buffered=2500; openHTTP=1",
                "Source: HLS", "HTTP: HTTP=200; Content-Type=application/dash+xml"))
        store.write(true, text)
        val summary = store.summary()
        val payload = DiagnosticsReport.payload(DiagnosticsReport.text(null, null, SourceDiagnostics(), null, summary))
        for (value in listOf("START_TIMEOUT", "TVP Seriale", "BUFFERING", "buffered=2500", "HLS",
                "HTTP=200", "application/dash+xml", "video/hevc", "nieobsługiwany", "__path_hidden__")) {
            assertTrue(payload, payload.contains(value))
        }
        assertFalse(payload.contains("private-code"))
    }

    @Test fun allStartupPhaseDurationsSurviveSummaryReportAndBoundedQrEncoding() {
        var now = 0L
        val timing = StartupTiming { now }
        now = 3
        timing.enter(StartupTiming.Phase.CLOSE_GRACE)
        now = 449
        timing.enter(StartupTiming.Phase.WAITING_PREVIOUS_CONNECTION)
        now = 549
        timing.enter(StartupTiming.Phase.PREPARING_BUFFERING)
        now = 600
        timing.enter(StartupTiming.Phase.RECOVERY_DELAY)
        now = 1000
        timing.enter(StartupTiming.Phase.WAITING_RECOVERY_CONNECTION)
        now = 1100
        timing.enter(StartupTiming.Phase.PREPARING_BUFFERING)
        now = 10080
        val text = PlaybackDiagnosticRecord.text("https://provider/play/private-code.m3u8", "live",
            "START_TIMEOUT", null, "video/hevc hvc1", "video/hevc [nieobsługiwany]", null, Instant.EPOCH,
            listOf("Channel: Stall 10s", *timing.detail().lines().toTypedArray(),
                "Ownership: requested stream session", "Player: BUFFERING; pos=0; buffered=0; openHTTP=1",
                "Source: HLS", "HTTP: HTTP=200; Content-Type=video/mp2t"))
        val store = PlaybackDiagnosticsStore(temporary.root)
        val legacy = text.replace(timing.detail(), timing.detail().replace("\n", "; "))
        assertFalse(text == legacy)
        for (saved in listOf(text, legacy)) {
            store.write(true, saved)
            store.write(false, record())
            val summary = store.summary()
            val report = DiagnosticsReport.text(null, null, SourceDiagnostics(), "Żółć 🎬 ".repeat(3000), summary)
            val payload = DiagnosticsReport.payload(report)
            assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DiagnosticsReport.QR_BYTES)
            assertTrue(payload.endsWith("[…raport skrócony]"))
            val matrix = DiagnosticsReport.qr(report)
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1
            }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
            assertEquals(payload, decoded)
            for (output in listOf(saved, summary, report, payload, decoded)) {
                for (value in listOf("phase=PREPARING_BUFFERING; totalMs=10080", "STOPPING_PREVIOUS=3ms",
                        "CLOSE_GRACE=446ms", "WAITING_PREVIOUS_CONNECTION=100ms", "PREPARING_BUFFERING=9031ms",
                        "RECOVERY_DELAY=400ms", "WAITING_RECOVERY_CONNECTION=100ms")) {
                    assertTrue(output, output.contains(value))
                }
                assertFalse(output.contains("private-code"))
            }
        }
    }

    @Test(expected = java.io.IOException::class)
    fun unwritableStoreDoesNotPretendToPersist() {
        PlaybackDiagnosticsStore(temporary.newFile()).write(true, record())
    }

    @Test fun richTimeoutEvidenceSurvivesSummaryAndDecodedQrWithStartupPhases() {
        val evidence = listOf("A ready=false in=50 out=0", "A skipIn=0 skipOut=3 drop=0", "V ready=true in=50 out=49",
            "V skipIn=0 skipOut=0 drop=1", "A rate=48000 ch=2", "Window start=1800000000000 default=15000",
            "Window duration=30000 live=true PDT=y", "Window offset=10000 liveOffset=15000")
        val phases = StartupTiming().detail().lines()
        val store = PlaybackDiagnosticsStore(temporary.root)
        for (kind in listOf("START_TIMEOUT", "SEEK_TIMEOUT", "REBUFFER_TIMEOUT")) {
            store.write(true, PlaybackDiagnosticRecord.text("https://provider/live/secret.m3u8", "live", kind, null,
                "audio/mpeg-L2", "video/avc", "ffmpegLavc60.3.100-mp3", Instant.EPOCH, evidence + phases))
            store.write(false, record())
            val report = DiagnosticsReport.text(null, null, SourceDiagnostics(), "Żółć🎬".repeat(3000), store.summary())
            val payload = DiagnosticsReport.payload(report)
            val matrix = DiagnosticsReport.qr(report)
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
            assertEquals(payload, decoded)
            assertTrue(payload.toByteArray().size <= DiagnosticsReport.QR_BYTES)
            val shareableEvidence = evidence.map { it.replace("1800000000000", "08:00:00Z") }
            for (line in shareableEvidence + phases) assertTrue(decoded, decoded.contains(line))
            assertFalse(Regex("\\d{7,}").containsMatchIn(decoded))
        }
    }

    @Test fun hlsRequestTraceAndPlaylistSummariesSurviveTheBoundedQr() {
        val store = PlaybackDiagnosticsStore(temporary.root)
        val hls = listOf("HTTP: HTTP=404", "HLS master: variants=2; selected=1",
            "HLS variant: 0 2000k 640x360 avc1+mp4a", "HLS variant: 1 500k 640x360 avc1+mp4a",
            "HLS renditions: count=1", "HLS rendition: audio/pl/uri=true/default=true",
            "HLS media: target=2000ms; segments=6; live=true; endlist=false",
            "HLS media detail: encryption=NONE; map=false; ext=ts") +
            List(12) { "HLS request: ${if (it == 0) "audio 404" else "segment 200"} ${it}ms ${if (it == 0) "m3u8" else "ts"}" }
        store.write(true, PlaybackDiagnosticRecord.text("https://provider/secret/123456789.m3u8", "live", "START_TIMEOUT",
            null, "video/avc", "audio/aac", null, Instant.EPOCH, StartupTiming { 0L }.detail().lines() + hls))
        val report = DiagnosticsReport.text(null, null, SourceDiagnostics(), "Brak", store.summary())
        val payload = DiagnosticsReport.payload(report)
        assertEquals(12, payload.lines().count { it.startsWith("HLS request:") })
        for (line in hls) assertTrue(payload, payload.contains(line))
        assertTrue(payload.toByteArray().size <= DiagnosticsReport.QR_BYTES)
        assertFalse(payload.contains("secret"))
        assertFalse(Regex("\\d{7,}").containsMatchIn(payload))
    }

    @Test fun maximumTimeoutAndCrashKeepEssentialLaterSectionsInDecodedQr() {
        val phases = StartupTiming { 0L }.detail().lines()
        val readiness = listOf("A ready=false in=2147483647 out=0", "V ready=true in=2147483647 out=2147483646")
        val store = PlaybackDiagnosticsStore(temporary.root)
        store.write(true, PlaybackDiagnosticRecord.text("https://provider.org/live/private-code.m3u8", "live",
            "START_TIMEOUT", null, "video/avc", "audio/mpeg-L2", "ffmpegLavc60.3.100-mp3", Instant.EPOCH,
            listOf("Channel: TVP Seriale") + readiness + phases + List(40) { "Extra $it: ${"Żółć🎬".repeat(40)}" }))
        store.write(false, record())
        val summary = store.summary()
        assertTrue(summary.substringBefore("Ostatnie poprawne odtwarzanie:").toByteArray().size >= 1450)
        val source = SourceEntity(name = "Review playlist", url = "https://provider.org/private-code", userAgent = "TV",
            rawCatchupChannels = 7, playlistUserAgent = "TV", playlistDeviceType = "FIRE_TV", epgUrl = "https://guide.org/epg.xml")
        val report = DiagnosticsReport.text(source, null, SourceDiagnostics(42, 40),
            "IllegalStateException: crash-marker ${"Żółć🎬".repeat(3000)}", summary)
        val payload = DiagnosticsReport.payload(report)
        val matrix = DiagnosticsReport.qr(report)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
        assertEquals(payload, decoded)
        assertEquals(payload, DiagnosticsReport.payload(payload))
        assertTrue(decoded.toByteArray().size <= DiagnosticsReport.QR_BYTES)
        val shareableReadiness = listOf("A ready=false in=2_147_483_647 out=0", "V ready=true in=2_147_483_647 out=2_147_483_646")
        for (line in phases + shareableReadiness + listOf("START_TIMEOUT", "Lista: Review playlist", "Kanały: 42",
                "M3U atrybuty archiwum: 7", "M3U User-Agent: własny (ukryty)", "M3U DEVICE TYPE: FIRE_TV",
                "EPG: guide[.]org/__path_hidden__", "Ostatni raport błędu:", "crash-marker")) {
            assertTrue(decoded, decoded.contains(line))
        }
        assertFalse(decoded.contains("private-code"))
        assertFalse(Regex("\\d{7,}").containsMatchIn(decoded))
        assertFalse(Regex("(?i)https?://|www\\.|\\w+\\.(com|org|pl|net|tv)\\b").containsMatchIn(decoded))
    }
}
