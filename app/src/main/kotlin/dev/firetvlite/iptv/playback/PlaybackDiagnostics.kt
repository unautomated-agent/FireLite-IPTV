package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.library.DiagnosticRedactor
import java.io.File
import java.net.URI
import java.time.Instant

internal fun utf8Limit(text: String, bytes: Int): String {
    if (text.toByteArray(Charsets.UTF_8).size <= bytes) return text
    val result = StringBuilder()
    var size = 0
    val points = text.codePoints().iterator()
    while (points.hasNext()) {
        val point = String(Character.toChars(points.nextInt()))
        val length = point.toByteArray(Charsets.UTF_8).size
        if (size + length > bytes - 3) break
        result.append(point)
        size += length
    }
    return "$result…"
}

internal object PlaybackDiagnosticRecord {
    private val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun text(url: String, mode: String, code: String?, error: Throwable?, selected: String,
        available: String, decoder: String?, time: Instant = Instant.now(), details: List<String> = emptyList()): String {
        val known = DiagnosticRedactor.secrets(listOf(url))
        fun safe(value: String, limit: Int): String {
            val masked = DiagnosticRedactor.mask(value, known)
            val hiddenPaths = urls.replace(masked) { match ->
                runCatching {
                    val uri = URI(match.value)
                    "${uri.scheme}://${uri.rawAuthority}/__path_hidden__"
                }.getOrDefault("[adres ukryty]")
            }
            return utf8Limit(hiddenPaths.replace(Regex("\\s+"), " "), limit)
        }
        val causes = ArrayList<String>()
        val seen = HashSet<Throwable>()
        var cause = error
        while (cause != null && seen.add(cause) && causes.size < 12) {
            causes.add("${cause.javaClass.name}: ${cause.message.orEmpty()}")
            cause = cause.cause
        }
        return buildString {
            appendLine("$time · $mode · ${code ?: "OK"}")
            details.forEach { appendLine(safe(it, 512)) }
            appendLine("Dekoder audio: ${safe(decoder ?: "nieznany", 256)}")
            appendLine("Wybrane: ${safe(selected.ifEmpty { "brak" }, 1500)}")
            if (error != null) appendLine("Przyczyny: ${safe(causes.joinToString(" ← "), 2500)}")
            appendLine("Dostępne: ${safe(available.ifEmpty { "brak" }, 2500)}")
            append("URL: ${safe(url, 512)}")
        }
    }
}

internal class PlaybackDiagnosticsStore(private val directory: File) {
    @Synchronized fun write(failed: Boolean, record: String) {
        val target = file(failed)
        val temporary = File(directory, "${target.name}.new")
        temporary.writeText(record, Charsets.UTF_8)
        check(temporary.renameTo(target)) { "Cannot save playback diagnostics" }
    }

    @Synchronized fun summary(): String = listOf(true, false).joinToString("\n") { failed ->
        val record = runCatching { file(failed).takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
        val compact = record?.lineSequence()?.map { line ->
            utf8Limit(line, when {
                line.startsWith("Wybrane:") || line.startsWith("Dostępne:") -> 120
                line.startsWith("Przyczyny:") -> 140
                else -> 100
            })
        }?.joinToString("\n") ?: "Brak"
        (if (failed) "Ostatni błąd odtwarzania:\n" else "Ostatnie poprawne odtwarzanie:\n") +
            utf8Limit(compact, if (failed) 850 else 300)
    }

    private fun file(failed: Boolean) = File(directory, if (failed) "playback-error.txt" else "playback-success.txt")
}
