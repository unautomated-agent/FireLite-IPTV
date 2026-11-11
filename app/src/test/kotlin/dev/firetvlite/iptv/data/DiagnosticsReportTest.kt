package dev.firetvlite.iptv.data

import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.epg.EpgState
import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import dev.firetvlite.iptv.data.library.DiagnosticsReport
import dev.firetvlite.iptv.data.library.SourceDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsReportTest {
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
            epgProgrammeCount = 123, epgRefreshedAt = 1)
        val report = DiagnosticsReport.text(source, EpgState.Failed("HTTP 403"),
            SourceDiagnostics(10, 8, mapOf("xc" to 7)), "Exception: secret-value for login-name")
        assertFalse(report.contains("secret-value"))
        assertFalse(report.contains("login-name"))
        assertTrue(report.contains("HTTP 403"))
        assertTrue(report.contains("Dopasowane kanały: 8"))
        assertTrue(report.contains("Archiwum: 7"))
        assertTrue(report.contains("Audycje: 123"))
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
}
