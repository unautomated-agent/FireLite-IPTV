package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.TimelineItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

internal data class HistoryFocus(val time: Long, val live: Boolean = false) {
    fun index(items: List<TimelineItem>): Int =
        if (live) items.indexOfLast { it.live }.coerceAtLeast(0)
        else ChannelTimeline.historyFocusIndex(items, time)
}

internal object HistoryNavigation {
    private val zone = ZoneId.of("Europe/Warsaw")
    private val polish = Locale.forLanguageTag("pl-PL")
    private val dateFormat = DateTimeFormatter.ofPattern("d.MM", polish)

    fun jumpDay(items: List<TimelineItem>, focus: HistoryFocus, delta: Int, now: Long): HistoryFocus {
        if (items.isEmpty() || delta == 0) return focus
        val current = Instant.ofEpochMilli(items[focus.index(items)].start).atZone(zone)
        val firstDay = Instant.ofEpochMilli(items.first().start).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = current.toLocalDate().plusDays(delta.toLong()).coerceIn(firstDay, today)
        if (day == current.toLocalDate()) return focus
        val time = day.atTime(current.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            .coerceIn(items.first().start, now)
        val candidates = items.indices.filter {
            Instant.ofEpochMilli(items[it].start).atZone(zone).toLocalDate() == day
        }
        val index = candidates.lastOrNull { items[it].start <= time }
            ?: candidates.firstOrNull() ?: ChannelTimeline.historyFocusIndex(items, time)
        return HistoryFocus(items[index].start, items[index].live)
    }

    fun dayLabel(time: Long, now: Long): String {
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Dziś"
            today.minusDays(1) -> "Wczoraj"
            else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, polish).removeSuffix(".")
                .replaceFirstChar { it.titlecase(polish) } + " " + dateFormat.format(day)
        }
    }
}
