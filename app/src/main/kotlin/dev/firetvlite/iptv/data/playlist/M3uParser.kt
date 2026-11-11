package dev.firetvlite.iptv.data.playlist

import java.io.BufferedReader
import java.io.Reader

data class ParsedChannel(
    val name: String,
    /** Empty if the playlist gives the channel no group. */
    val group: String,
    val url: String,
    val logoUrl: String?,
    val tvgId: String?,
    val catchupType: String?,
    val catchupSource: String?,
    val catchupDays: Int,
    val tvgName: String? = null,
)

/**
 * Reads M3U/M3U8 playlists line by line, so even very large lists need little memory.
 *
 * Understood: `#EXTINF` with `group-title`, `tvg-id`, `tvg-name`, `tvg-logo` and the catch-up attributes
 * `catchup`, `catchup-source`, `catchup-days` (also `tvg-rec` and `timeshift`), `#EXTGRP`, and catch-up
 * defaults on the `#EXTM3U` header line. Everything else starting with `#` is ignored.
 */
object M3uParser {

    private class Pending(
        val name: String = "",
        val group: String = "",
        val logoUrl: String? = null,
        val tvgId: String? = null,
        val tvgName: String? = null,
        val catchupType: String? = null,
        val catchupSource: String? = null,
        val catchupDays: Int? = null,
    )

    private class Defaults(
        val catchupType: String? = null,
        val catchupSource: String? = null,
        val catchupDays: Int? = null,
    )

    fun parse(
        source: Reader,
        onEpgUrls: (List<String>) -> Unit = {},
        onChannel: (ParsedChannel) -> Unit,
    ) {
        val reader = source as? BufferedReader ?: BufferedReader(source, 1 shl 16)
        var defaults = Defaults()
        var pending: Pending? = null
        var firstLine = true
        val epgUrls = linkedSetOf<String>()

        while (true) {
            var line = reader.readLine() ?: break
            if (firstLine) {
                firstLine = false
                if (line.startsWith("\uFEFF")) line = line.substring(1)
            }
            line = line.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (!line.startsWith("#EXTINF", true)) {
                    val values = listOf("x-tvg-url", "url-tvg", "tvg-url").mapNotNull { attribute(line.substring(1), it) }
                    if (values.isNotEmpty()) {
                        values.flatMap { it.split(Regex("[,\\s]+")) }.filter { it.isNotBlank() }.forEach { epgUrls.add(it) }
                        onEpgUrls(epgUrls.toList())
                    }
                }
                when {
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        defaults = readDefaults(line)
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> pending = readInfo(line)
                    line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        val group = line.substring("#EXTGRP:".length).trim()
                        val current = pending
                        if (current != null && current.group.isEmpty() && group.isNotEmpty()) {
                            pending = Pending(
                                name = current.name,
                                group = group,
                                logoUrl = current.logoUrl,
                                tvgId = current.tvgId,
                                tvgName = current.tvgName,
                                catchupType = current.catchupType,
                                catchupSource = current.catchupSource,
                                catchupDays = current.catchupDays,
                            )
                        }
                    }
                }
                continue
            }

            // Not a comment: the stream address of the channel described by the last #EXTINF.
            if (looksLikeUrl(line)) {
                onChannel(toChannel(pending ?: Pending(), line, defaults))
            }
            pending = null
        }
    }

    private fun readDefaults(line: String): Defaults {
        val source = attribute(line, "catchup-source")
        val type = attribute(line, "catchup") ?: attribute(line, "catchup-type") ?: if (source != null) "default" else null
        return Defaults(type, source, readCatchupDays(line))
    }

    private fun readInfo(line: String): Pending {
        val rest = line.substringAfter(':', "")
        val comma = firstCommaOutsideQuotes(rest)
        val attrs = if (comma >= 0) rest.substring(0, comma) else rest
        var name = if (comma >= 0) rest.substring(comma + 1).trim() else ""
        if (name.isEmpty()) name = attribute(attrs, "tvg-name").orEmpty()
        return Pending(
            name = name,
            group = attribute(attrs, "group-title").orEmpty(),
            logoUrl = attribute(attrs, "tvg-logo")?.takeIf { it.isNotEmpty() },
            tvgId = attribute(attrs, "tvg-id")?.takeIf { it.isNotEmpty() },
            tvgName = attribute(attrs, "tvg-name")?.takeIf { it.isNotEmpty() },
            catchupType = (attribute(attrs, "catchup") ?: attribute(attrs, "catchup-type"))?.takeIf { it.isNotEmpty() },
            catchupSource = attribute(attrs, "catchup-source")?.takeIf { it.isNotEmpty() },
            catchupDays = readCatchupDays(attrs),
        )
    }

    private fun toChannel(info: Pending, url: String, defaults: Defaults): ParsedChannel {
        val source = info.catchupSource ?: defaults.catchupSource
        val declaredType = info.catchupType ?: defaults.catchupType
        val days = info.catchupDays ?: defaults.catchupDays
            ?: if (declaredType != null || source != null) 7 else 0
        val type = declaredType?.takeUnless { it.equals("true", true) || it == "1" }
            ?: when {
                source != null -> "default"
                days > 0 && XtreamAddress.stream(url) != null -> "xc"
                days > 0 -> "shift"
                else -> null
            }
        return ParsedChannel(
            name = info.name.ifEmpty { url },
            group = info.group,
            url = url,
            logoUrl = info.logoUrl,
            tvgId = info.tvgId,
            catchupType = type,
            catchupSource = source,
            catchupDays = days,
            tvgName = info.tvgName,
        )
    }

    private fun readCatchupDays(attrs: String): Int? {
        val days = attribute(attrs, "catchup-days") ?: attribute(attrs, "tvg-rec") ?: attribute(attrs, "timeshift")
        val value = (days ?: attribute(attrs, "catchup-time"))?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return (if (days == null && value >= 86_400) value / 86_400 else value).toInt().coerceIn(0, 365)
    }

    /** Index of the first comma that is not inside quotes, or -1. */
    internal fun firstCommaOutsideQuotes(text: String): Int {
        var quote: Char? = null
        for (index in text.indices) {
            val c = text[index]
            if (c == quote) {
                quote = null
            } else if (quote == null && (c == '"' || c == '\'')) {
                quote = c
            } else if (c == ',' && quote == null) {
                return index
            }
        }
        return -1
    }

    /** Value of key="..." (key is matched ignoring case, at the start of a word), or null. */
    internal fun attribute(attrs: String, key: String): String? {
        val match = Regex("(?:^|[\\s:])${Regex.escape(key)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))",
            RegexOption.IGNORE_CASE).find(attrs) ?: return null
        return match.groups[1]?.value?.trim() ?: match.groups[2]?.value?.trim() ?: match.groups[3]?.value?.trim()
    }

    /** True for "scheme://..." at the start of the line, e.g. http://, https://, udp://. */
    internal fun looksLikeUrl(line: String): Boolean {
        val separator = line.indexOf("://")
        if (separator <= 0) return false
        return line.substring(0, separator).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
    }
}
