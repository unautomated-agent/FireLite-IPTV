package dev.firetvlite.iptv.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.db.ArchiveTypeCount
import dev.firetvlite.iptv.data.epg.EpgState
import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import dev.firetvlite.iptv.data.library.DiagnosticsReport
import dev.firetvlite.iptv.data.library.SourceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
    @Test fun boundedQrKeepsStallCrashAndProcessExitEvidence() {
        val health = "Main-thread stall: 3500ms\nMain thread: java.util.regex.Pattern.compile\n" +
            "Uncaught crash: java.lang.OutOfMemoryError\nThread: background-worker\nProcess exit: LOW_MEMORY\n" +
            "frame\n".repeat(2000)
        val text = DiagnosticsReport.text(null, null, SourceDiagnostics(), health)
        val qr = DiagnosticsReport.payload(text)
        assertTrue(qr.toByteArray().size <= DiagnosticsReport.QR_BYTES)
        for (expected in listOf("Main-thread stall:", "Main thread:", "Uncaught crash:", "Process exit: LOW_MEMORY"))
            assertTrue(qr, qr.contains(expected))
    }

    @Test fun timestampsAreCompactAndAllSectionsAreFreeOfLongDigitRunsEvenAfterQrDecoding() {
        val source = SourceEntity(name = "Playlist 1794475151000", url = "https://provider.org/private-code", userAgent = "TV",
            epgRefreshedAt = 1794475180688, epgProgrammeCount = Int.MAX_VALUE, xtreamArchiveCount = Int.MAX_VALUE,
            rawCatchupChannels = Int.MAX_VALUE, playlistDeviceType = "Device 123456789012345678901234567890")
        val playback = "Ostatni błąd odtwarzania:\n2026-11-12T09:19:40.688Z · START_TIMEOUT\n" +
            "Startup: totalMs=12345678\nWindow start=1794475151000 default=12345678\n" +
            "Window duration=123456789 live=true PDT=y\nWindow offset=-9223372036854775807 liveOffset=12345678\n" +
            "A ready=true in=2147483647 out=10\nV ready=false in=0 out=0\n" +
            "Ostatnie poprawne odtwarzanie:\n2026-11-12T09:19:40.688Z · OK\nURL: https://working.tv/private-code"
        for (extra in listOf("", "Żółć🎬 1794475151000 ".repeat(3000))) {
            val report = DiagnosticsReport.text(source, EpgState.Failed("HTTP 123456789"),
                SourceDiagnostics(Int.MAX_VALUE, Int.MAX_VALUE), "Crash 9223372036854775807 $extra", playback)
            val payload = DiagnosticsReport.payload(report)
            val matrix = DiagnosticsReport.qr(report)
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
            val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
            assertEquals(payload, decoded)
            assertEquals(payload, DiagnosticsReport.payload(payload))
            assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DiagnosticsReport.QR_BYTES)
            for (output in listOf(report, payload, decoded)) {
                assertFalse(output, Regex("\\d{7,}").containsMatchIn(output))
                assertFalse(output.contains("private-code"))
                assertFalse(output.contains("2026-11-12T09:19:40.688Z"))
                assertTrue(output, output.contains("11-12 09:19:40Z"))
                assertTrue(output, output.contains("Window start=09:19:11Z"))
                assertTrue(output, output.contains("A ready=true in=2_147_483_647 out=10"))
                assertTrue(output, output.contains("Ostatni raport błędu:"))
            }
            assertTrue(report.contains("totalMs=12_345_678"))
            assertTrue(report.contains("9_223_372_036_854_775_807"))
        }
    }

    @Test fun numericFormattingHandlesEmptyInvalidAndBoundaryValuesWithoutChangingShortNumbers() {
        assertEquals("", DiagnosticsReport.payload(""))
        assertEquals("123456 1_234_567 -1_234_567", DiagnosticsReport.payload("123456 1234567 -1234567"))
        assertEquals("Window start=123_456_789_012_345_678_901_234_567_890",
            DiagnosticsReport.payload("Window start=123456789012345678901234567890"))
        assertEquals("11-12 09:19:40Z", DiagnosticsReport.payload("2026-11-12T10:19:40.688+01:00"))
        assertFalse(Regex("\\d{7,}").containsMatchIn(DiagnosticsReport.payload("2026-99-99T99:99:99.123456789Z")))
    }

    @Test fun migratedSourcesHaveUnknownResponseMetadataUntilASuccessfulReimport() {
        val source = SourceEntity(name = "M3U", url = "https://h/list", userAgent = "FireLite/2.0",
            refreshedAt = 1234)
        val text = DiagnosticsReport.text(source, null, SourceDiagnostics(), null)
        for (label in listOf("M3U atrybuty archiwum", "M3U User-Agent", "M3U DEVICE TYPE")) {
            assertTrue(text.contains("$label: Nieznany – brak importu po aktualizacji"))
        }
        assertFalse(text.contains("Brak komentarza"))
        assertFalse(text.contains("M3U atrybuty archiwum: 0"))
        assertFalse(text.contains("M3U User-Agent: własny"))
    }

    @Test fun successfulReimportWithoutAttributesOrCommentShowsRecordedAbsence() {
        val source = SourceEntity(name = "M3U", url = "https://h/list", userAgent = "FireLite/2.0",
            rawCatchupChannels = 0, playlistUserAgent = "FireLite/2.0", playlistDeviceType = "")
        val text = DiagnosticsReport.text(source, null, SourceDiagnostics(), null)
        assertTrue(text.contains("M3U atrybuty archiwum: 0"))
        assertTrue(text.contains("M3U User-Agent: FireLite/2.0"))
        assertTrue(text.contains("M3U DEVICE TYPE: Brak komentarza"))
        assertFalse(text.contains("brak importu po aktualizacji"))
    }

    @Test fun customPlaylistUserAgentsAreNeverIncludedInTextOrQrPayload() {
        for (agent in listOf("Bearer private-ua-value", "FireLite/2.0 private-ua-value", "firelite/2.0",
                "Mozilla/5.0\nprivate-ua-value", "")) {
            val source = SourceEntity(name = "M3U", url = "https://h/list", userAgent = agent,
                rawCatchupChannels = 0, playlistUserAgent = agent)
            val text = DiagnosticsReport.text(source, null, SourceDiagnostics(), null)
            assertTrue(text.contains("M3U User-Agent: własny (ukryty)"))
            assertFalse(text.contains("private-ua-value"))
            assertFalse(DiagnosticsReport.payload(text).contains("private-ua-value"))
            if (agent.isNotEmpty()) assertFalse(text.contains(agent))
        }
    }

    @Test fun providerResponseDiagnosticsShowActualImportUaAttributesAndDeviceType() {
        val source = SourceEntity(name = "M3U", url = "https://h/list", userAgent = "edited-UA",
            rawCatchupChannels = 334, playlistUserAgent = "FireLite/2.0", playlistDeviceType = "NONSPECIFIC")
        val text = DiagnosticsReport.text(source, null, SourceDiagnostics(), null)
        assertTrue(text.contains("M3U atrybuty archiwum: 334"))
        assertTrue(text.contains("M3U User-Agent: FireLite/2.0"))
        assertTrue(text.contains("M3U DEVICE TYPE: NONSPECIFIC"))
        assertFalse(text.contains("edited-UA"))
        val unknown = DiagnosticsReport.text(null, null, SourceDiagnostics(), null)
        assertTrue(unknown.contains("M3U atrybuty archiwum: —"))
        assertTrue(unknown.contains("M3U User-Agent: —"))
    }

    @Test fun aggregateDiagnosticsNeedOnlyCountsAndOneCredentialSample() {
        val stats = SourceDiagnostics.from(12000, 11000, listOf(ArchiveTypeCount("xc", 9000)),
            "https://provider/live/private-user/private-pass/12.ts")
        assertEquals(12000, stats.channelCount)
        assertEquals(9000, stats.archiveTypes["xc"])
        assertTrue(stats.secrets.contains("private-user"))
        assertEquals(SourceDiagnostics(), SourceDiagnostics.from(0, 0, emptyList(), null))
    }

    @Test fun masksAllCredentialAliasesInQueriesAndPlainCrashMessages() {
        for (key in listOf("token", "key", "api_key", "apikey", "auth", "pass", "password", "user", "username", "login", "secret", "sig", "signature")) {
            val secret = "private-$key-value"
            val url = "https://provider/xmltv.php?${key.uppercase()}=$secret"
            val result = DiagnosticRedactor.mask("$url\nException: $secret\n$key='$secret'")
            assertFalse(result, result.contains(secret))
            assertFalse(DiagnosticRedactor.mask("$key=$secret").contains(secret))
        }
        assertEquals("http://provider/__path_hidden__", DiagnosticRedactor.mask("http://private-user:private-pass@provider/path"))
        for (path in listOf("live/private-user/private-pass/12.ts", "private-user/private-pass/12", "timeshift/private-user/private-pass/60/date/12.ts")) {
            val result = DiagnosticRedactor.mask("http://provider/$path")
            assertFalse(result.contains("private-user"))
            assertFalse(result.contains("private-pass"))
        }
    }

    @Test fun masksCredentialsInUrlsPathsPlainErrorsAndEncodedQueryValues() {
        val input = """
            http://provider/get.php?username=private-user&password=p%40ssword
            https://private-user:p%40ssword@provider/guide.xml
            https://provider/live/private-user/p%40ssword/12.ts
            https://provider/timeshift/private-user/p%40ssword/60/2026-11-11:12-00/12.ts
            https://provider/private-user/p%40ssword/12.m3u8
            password=p@ssword username='another-user' token=another-secret
        """.trimIndent()
        val result = DiagnosticRedactor.mask(input)
        listOf("private-user", "p%40ssword", "p@ssword", "another-user", "another-secret").forEach {
            assertFalse(result, result.contains(it))
        }
        assertTrue(result.contains("provider"))
        assertTrue(result.contains("/live/***/***/12.ts"))
        assertTrue(DiagnosticRedactor.mask("Bad address http://[invalid/").contains("adres ukryty"))
    }

    @Test fun reportsActiveSourceGuideArchiveMatchingAndFailureWithMaskedCrash() {
        val source = SourceEntity(name = "Provider", url = "https://provider/get.php?username=login-name&password=secret-value",
            userAgent = "TV", discoveredEpgUrls = "https://provider/xmltv.php?username=login-name&password=secret-value",
            epgProgrammeCount = 123, epgRefreshedAt = 1, xtreamStatus = "Lista API: OK",
            xtreamArchiveCount = 7, xtreamTimezone = "Europe/Warsaw")
        val report = DiagnosticsReport.text(source, EpgState.Failed("HTTP 403"),
            SourceDiagnostics(10, 8, mapOf("xc" to 7)), "Exception: secret-value for login-name")
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("login-name"))
        assertTrue(report.contains("HTTP 403"))
        assertTrue(report.contains("Dopasowane kanały: 8"))
        assertTrue(report.contains("Archiwum: 7"))
        assertTrue(report.contains("Audycje: 123"))
        assertTrue(report.contains("Xtream API: Lista API: OK"))
        assertTrue(report.contains("Xtream tv_archive: 7 · Strefa XC: Europe/Warsaw"))
        assertTrue(DiagnosticsReport.text(null, null, SourceDiagnostics(), null).contains("Brak aktywnej listy"))
    }

    @Test fun truncatedPolishCrashReportFitsAndQrDecodesExactly() {
        val text = "Diagnostyka: Łódź · Żółć\n" + "Błąd audycji: wyjątek w wątku\n".repeat(300)
        val payload = DiagnosticsReport.payload(text)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= DiagnosticsReport.QR_BYTES)
        assertTrue(payload.endsWith("[…raport skrócony]"))
        val matrix = DiagnosticsReport.qr(text)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
        assertEquals(payload, decoded.text)
    }

    @Test fun providerUrlsRemainInlineInACompletePlainTextQrReport() {
        val source = SourceEntity(name = "Provider", url = "https://provider/playlist/private-access-code", userAgent = "TV")
        val report = DiagnosticsReport.text(source, EpgState.Failed("HTTP 403"), SourceDiagnostics(10, 8), null,
            "Ostatni błąd odtwarzania:\nChannel: TVP Seriale\nURL: https://my.teleelevidenie.com/play/hls-custom-c123-private-code.m3u8")
        val payload = DiagnosticsReport.payload(report)
        assertTrue(payload.contains("my[.]teleelevidenie[.]com/__path_hidden__"))
        assertFalse(payload.contains("https://"))
        assertFalse(payload.contains("private-code"))
        assertFalse(payload.contains("private-access-code"))
        for (label in listOf("FireLite", "TVP Seriale", "Lista:", "Kanały:", "EPG stan:", "HTTP 403", "Archiwum:", "Ostatni raport błędu:")) {
            assertTrue(payload, payload.contains(label))
        }
        val matrix = DiagnosticsReport.qr(report)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels))))
        assertEquals(payload, decoded.text)
        assertEquals(payload, DiagnosticsReport.payload(payload))
    }

    @Test fun everyDisplayedAndDecodedSectionIsFreeOfPhoneDetectedAddresses() {
        val source = SourceEntity(name = "www.playlist.net", url = "https://playlist.net/private-code", userAgent = "TV",
            epgUrl = "https://iptv-epg.org/guide.xml,https://backup.pl/epg.xml",
            rawCatchupChannels = 0, playlistDeviceType = "company.tv")
        val report = DiagnosticsReport.text(source, EpgState.Failed("www.failed.org HTTP 403"), SourceDiagnostics(),
            "Exception from crash.example.pl; https://crash.net/private-code",
            "Ostatni błąd odtwarzania:\nURL: https://my.teleelevidenie.com/play/private-code\n" +
                "Ostatnie poprawne odtwarzanie:\nURL: https://working.tv/private-code")
        val payload = DiagnosticsReport.payload(report)
        val matrix = DiagnosticsReport.qr(report)
        val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else -1 }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))).text
        val detectors = Regex("(?i)https?://|www\\.|\\w+\\.(com|org|pl|net|tv)\\b")
        for (text in listOf(report, payload, decoded)) {
            assertFalse(text, detectors.containsMatchIn(text))
            assertFalse(text.contains("private-code"))
            for (host in listOf("my[.]teleelevidenie[.]com", "working[.]tv", "iptv-epg[.]org", "www[.]playlist[.]net",
                    "backup[.]pl", "crash[.]example[.]pl", "crash[.]net", "company[.]tv")) assertTrue(text, text.contains(host))
        }
        assertEquals(payload, decoded)
        assertEquals(payload, DiagnosticsReport.payload(payload))
        assertFalse(detectors.containsMatchIn(DiagnosticsReport.payload("https[:]//legacy.org/__path_hidden__")))
        assertFalse(detectors.containsMatchIn(DiagnosticsReport.payload("HTTPS://WWW.EXAMPLE.COM/path")))
        assertFalse(detectors.containsMatchIn(DiagnosticsReport.payload("broken https:// www. 例子.com")))
        assertTrue(DiagnosticsReport.payload("host 192.168.1.1").contains("192[.]168[.]1[.]1"))
        assertEquals("", DiagnosticsReport.payload(""))
    }
}
