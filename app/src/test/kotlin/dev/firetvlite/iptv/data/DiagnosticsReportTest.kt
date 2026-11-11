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
        assertTrue(payload.contains("https[:]//my.teleelevidenie.com/__path_hidden__"))
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
}
