package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ChannelEntity
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

    fun isSupported(channel: ChannelEntity): Boolean = channel.catchupDays > 0 && typeOf(channel) != null

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

            else -> null
        }
    }

    private fun typeOf(channel: ChannelEntity): String? {
        val type = channel.catchupType?.trim()?.lowercase(Locale.ROOT)
        return type?.takeIf { it in SUPPORTED_TYPES }
    }

    private fun flussonic(stream: String, start: Long, duration: Long): String? {
        val archive = "archive-$start-$duration"
        return when {
            stream.endsWith("/index.m3u8") -> stream.removeSuffix("index.m3u8") + "$archive.m3u8"
            stream.endsWith("/mpegts") -> stream.removeSuffix("mpegts") + "$archive.ts"
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

    private val SUPPORTED_TYPES = setOf("default", "append", "shift", "timeshift", "flussonic", "fs")
}
