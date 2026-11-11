package dev.firetvlite.iptv.data.library

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dev.firetvlite.iptv.data.db.ArchiveTypeCount
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.epg.EpgSources
import dev.firetvlite.iptv.data.epg.EpgState
import dev.firetvlite.iptv.data.playlist.XtreamAddress
import java.net.URI
import java.net.URLDecoder
import java.time.Instant

data class SourceDiagnostics(val channelCount: Int = 0, val matched: Int = 0,
    val archiveTypes: Map<String, Int> = emptyMap(), val secrets: Set<String> = emptySet()) {
    companion object {
        fun from(channelCount: Int, matched: Int, types: List<ArchiveTypeCount>, sample: String?): SourceDiagnostics =
            SourceDiagnostics(channelCount, matched, types.associate { it.type to it.count },
                DiagnosticRedactor.secrets(listOfNotNull(sample)))
    }
}

object DiagnosticRedactor {
    private val urlPattern = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val sensitive = setOf("token", "key", "api_key", "apikey", "auth", "authorization", "pass", "password",
        "user", "username", "login", "secret", "sig", "signature")
    private fun decoded(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    fun secrets(urls: List<String>): Set<String> = buildSet {
        urls.forEach { url ->
            runCatching { URI(url) }.getOrNull()?.let { uri ->
                uri.rawUserInfo?.split(':')?.forEach { add(it); add(decoded(it)) }
                uri.rawQuery.orEmpty().split('&').forEach { pair ->
                    if (decoded(pair.substringBefore('=')).lowercase() in sensitive) {
                        val value = pair.substringAfter('=', "")
                        add(value); add(decoded(value))
                    }
                }
            }
            (XtreamAddress.stream(url) ?: XtreamAddress.playlist(url))?.let {
                add(it.user); add(it.password)
            }
        }
    }.filter { it.isNotEmpty() && it != "***" }.toSet()

    fun mask(text: String, known: Set<String> = emptySet()): String {
        val urls = urlPattern.findAll(text).map { it.value }.toList()
        val credentials = known + secrets(urls)
        var result = urlPattern.replace(text) { match ->
            val uri = runCatching { URI(match.value) }.getOrNull()
            if (uri?.host == null) "[adres ukryty]" else {
                var path = uri.rawPath.orEmpty()
                val parts = path.trim('/').split('/')
                val prefix = parts.firstOrNull()?.lowercase()
                val credentialIndex = when {
                    prefix in setOf("live", "movie", "series", "timeshift") && parts.size >= 4 -> 1
                    XtreamAddress.stream(match.value) != null -> 0
                    else -> null
                }
                if (credentialIndex != null) path = "/" + parts.mapIndexed { i, value ->
                    if (i == credentialIndex || i == credentialIndex + 1) "***" else value
                }.joinToString("/")
                val query = uri.rawQuery?.split('&')?.joinToString("&") { it.substringBefore('=') + "=***" }
                val host = uri.host.let { if (it.contains(':') && !it.startsWith('[')) "[$it]" else it }
                "${uri.scheme}://$host${if (uri.port >= 0) ":${uri.port}" else ""}$path" +
                    (query?.let { "?$it" } ?: "")
            }
        }
        result = Regex("(?i)\\b(${sensitive.joinToString("|")})[\"']?\\s*[:=]\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s&,;]+)")
            .replace(result) { "${it.groupValues[1]}=***" }
        credentials.sortedByDescending { it.length }.forEach { result = result.replace(it, "***") }
        return result
    }
}

object DiagnosticsReport {
    const val QR_BYTES = 1800

    fun text(source: SourceEntity?, state: EpgState?, stats: SourceDiagnostics, crash: String?): String {
        val urls = source?.let { runCatching { EpgSources.urls(it.epgUrl, it.discoveredEpgUrls, it.url) }.getOrDefault(emptyList()) }.orEmpty()
        val status = when (state) {
            EpgState.Running -> "Wczytywanie"
            is EpgState.Failed -> "Błąd: ${state.detail}"
            is EpgState.Done -> "Gotowy"
            null -> if ((source?.epgRefreshedAt ?: 0) > 0) "Gotowy (zapisany)" else "Brak importu"
        }
        val raw = buildString {
            appendLine("FireLite · Diagnostyka")
            appendLine("Lista: ${source?.name ?: "Brak aktywnej listy"}")
            appendLine("Kanały: ${stats.channelCount}")
            appendLine("EPG: ${urls.joinToString("\n").ifEmpty { "Nie wykryto adresu" }}")
            appendLine("EPG stan: $status")
            appendLine("EPG import: ${source?.epgRefreshedAt?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) } ?: "—"}")
            appendLine("Audycje: ${source?.epgProgrammeCount ?: 0} · Dopasowane kanały: ${stats.matched}")
            appendLine("Archiwum: ${stats.archiveTypes.values.sum()} · Typy: ${stats.archiveTypes.entries.joinToString { "${it.key}: ${it.value}" }.ifEmpty { "brak" }}")
            appendLine("Wykrycie z metadanych; dostępność u dostawcy niepotwierdzona. Bez testowania strumieni.")
            appendLine("Ostatni raport błędu:")
            append(crash ?: "Brak")
        }
        val known = stats.secrets + DiagnosticRedactor.secrets(source?.let {
            listOf(it.url, it.epgUrl, *it.discoveredEpgUrls.split(Regex("[,\\s]+")).toTypedArray())
        }.orEmpty())
        return DiagnosticRedactor.mask(raw, known)
    }

    fun payload(text: String): String {
        val masked = DiagnosticRedactor.mask(text)
        if (masked.toByteArray(Charsets.UTF_8).size <= QR_BYTES) return masked
        val suffix = "\n[…raport skrócony]"
        var prefix = masked.take(QR_BYTES - suffix.toByteArray(Charsets.UTF_8).size)
        while ((prefix + suffix).toByteArray(Charsets.UTF_8).size > QR_BYTES) prefix = prefix.dropLast(1)
        return prefix + suffix
    }

    fun qr(text: String): BitMatrix = QRCodeWriter().encode(payload(text), BarcodeFormat.QR_CODE, 600, 600,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
}
