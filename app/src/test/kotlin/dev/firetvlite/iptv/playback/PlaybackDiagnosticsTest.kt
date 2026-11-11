package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.library.DiagnosticsReport
import dev.firetvlite.iptv.data.library.SourceDiagnostics
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

    @Test(expected = java.io.IOException::class)
    fun unwritableStoreDoesNotPretendToPersist() {
        PlaybackDiagnosticsStore(temporary.newFile()).write(true, record())
    }
}
