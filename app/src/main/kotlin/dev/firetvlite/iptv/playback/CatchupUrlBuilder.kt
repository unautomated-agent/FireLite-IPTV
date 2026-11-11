package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.playlist.XtreamAddress
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the address of a past moment ("catch-up", archive) of a channel from the settings in an M3U playlist.
 *
 * Supported `catchup` types:
 * - `default`: `catchup-source` is a complete address with placeholders.
 * - `append`: `catchup-source` is appended to the stream address.
 * - `shift` / `timeshift`: `?utc=<start>&lutc=<now>` is appended to the stream address.
 * - `flussonic` / `fs`: the stream's `index.m3u8` or `mpegts` becomes `archive-<start>-<duration>`.
 *
 * Placeholders (both `${name}` and `{name}`): start/utc, end/utcend, timestamp/lutc, duration, offset,
 * and the date parts `{Y}` `{m}` `{d}` `{H}` `{M}` `{S}` of the start time (UTC).
 */
object CatchupUrlBuilder {

    fun isSupported(channel: ChannelEntity): Boolean = channel.catchupDays > 0 &&
        build(channel, 1_700_000_000, 1_700_000_060, 60) != null

    /** The archive address for [startEpochSec], or null if the channel's catch-up type is unknown. */
    fun build(
        channel: ChannelEntity,
        startEpochSec: Long,
        nowEpochSec: Long,
        durationSec: Long,
    ): String? {
        val type = typeOf(channel) ?: return null
        val stream = channel.streamUrl
        val source = channel.catchupSource.orEmpty()
        if (source.isNotEmpty() && type in setOf("shift", "timeshift", "flussonic", "fs", "xc")) {
            val expanded = expand(source, startEpochSec, nowEpochSec, durationSec)
            return if (expanded.contains("://")) expanded else stream + expanded
        }
        return when (type) {
            "default" ->
                if (source.isEmpty()) null
                else expand(source, startEpochSec, nowEpochSec, durationSec).let { expanded ->
                    if (expanded.contains("://")) expanded else stream + expanded
                }

            "append" ->
                if (source.isEmpty()) null else stream + expand(source, startEpochSec, nowEpochSec, durationSec)

            "shift", "timeshift" -> {
                val separator = if (stream.contains('?')) "&" else "?"
                "$stream${separator}utc=$startEpochSec&lutc=$nowEpochSec"
            }

            "flussonic", "fs" -> flussonic(stream, startEpochSec, durationSec)

            "xc" -> {
                val address = XtreamAddress.stream(stream) ?: return null
                fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                val minutes = (durationSec.coerceAtLeast(1) / 60 + if (durationSec % 60 != 0L) 1 else 0).coerceAtLeast(1)
                val zone = runCatching { ZoneId.of(channel.catchupTimezone) }.getOrDefault(ZoneId.of("UTC"))
                val time = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT).withZone(zone)
                    .format(Instant.ofEpochSecond(startEpochSec))
                "${address.origin}/timeshift/${segment(address.user)}/${segment(address.password)}/$minutes/$time/${address.streamId}.${address.extension}"
            }

            else -> null
        }
    }

    private fun typeOf(channel: ChannelEntity): String? {
        val type = channel.catchupType?.trim()?.lowercase(Locale.ROOT)
            ?: if (!channel.catchupSource.isNullOrBlank()) "default" else null
        return type?.takeIf { it in SUPPORTED_TYPES }
    }

    private fun flussonic(stream: String, start: Long, duration: Long): String? {
        val archive = "archive-$start-$duration"
        val path = stream.substringBefore('?')
        val query = stream.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
        return when {
            path.endsWith("/index.m3u8") -> path.removeSuffix("index.m3u8") + "$archive.m3u8$query"
            path.endsWith("/mpegts") -> path.removeSuffix("mpegts") + "$archive.ts$query"
            else -> null
        }
    }

    internal fun expand(template: String, start: Long, now: Long, duration: Long): String {
        val utc = TimeZone.getTimeZone("UTC")
        val calendar = java.util.Calendar.getInstance(utc, Locale.ROOT).apply { timeInMillis = start * 1000 }
        fun two(value: Int) = value.toString().padStart(2, '0')

        val values = mapOf(
            "start" to start.toString(),
            "utc" to start.toString(),
            "end" to (start + duration).toString(),
            "utcend" to (start + duration).toString(),
            "timestamp" to now.toString(),
            "lutc" to now.toString(),
            "duration" to duration.toString(),
            "offset" to (now - start).toString(),
            "Y" to calendar.get(java.util.Calendar.YEAR).toString(),
            "m" to two(calendar.get(java.util.Calendar.MONTH) + 1),
            "d" to two(calendar.get(java.util.Calendar.DAY_OF_MONTH)),
            "H" to two(calendar.get(java.util.Calendar.HOUR_OF_DAY)),
            "M" to two(calendar.get(java.util.Calendar.MINUTE)),
            "S" to two(calendar.get(java.util.Calendar.SECOND)),
        )
        var result = template
        for ((name, value) in values) {
            result = result.replace("\${$name}", value).replace("{$name}", value)
        }
        return result
    }

    private val SUPPORTED_TYPES = setOf("default", "append", "shift", "timeshift", "flussonic", "fs", "xc")
}
