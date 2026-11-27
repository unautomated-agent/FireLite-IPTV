package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.PlaybackTime
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
    data class InitialPosition(val index: Int, val offset: Int)

    /** A live stream runs up to about a minute behind the clock; further back counts as watching the past. */
    const val BEHIND_LIVE_MS = 120_000L

    /** Opens on the programme being watched when playback is behind live ([archive] or far back in the live window), otherwise on the live row. */
    fun initialFocus(playing: Boolean, archive: Boolean, watched: Long, now: Long, live: TimelineItem?): HistoryFocus =
        if (playing && watched > 0 && watched < now - (if (archive) PlaybackTime.LIVE_TOLERANCE_MS else BEHIND_LIVE_MS) &&
            (live == null || watched < live.start))
            HistoryFocus(watched)
        else HistoryFocus(now, live = true)

    /** Places the focused row three quarters down (live: the past is above) or, with [centred], in the middle. */
    fun initialPosition(liveIndex: Int, viewportHeight: Int, rowHeight: Int, spacing: Int, centred: Boolean = false): InitialPosition {
        if (liveIndex <= 0 || viewportHeight <= 0 || rowHeight <= 0) return InitialPosition(liveIndex.coerceAtLeast(0), 0)
        val stride = rowHeight.toLong() + spacing.coerceAtLeast(0)
        val target = if (centred) (viewportHeight.toLong() - rowHeight) / 2 else viewportHeight.toLong() * 3 / 4
        val before = ((target + stride - 1) / stride).coerceAtMost(liveIndex.toLong()).toInt()
        return InitialPosition(liveIndex - before, (before * stride - target).coerceAtLeast(0).toInt())
    }

    private val zone = ZoneId.of("Europe/Warsaw")
    private val polish = Locale.forLanguageTag("pl-PL")
    private val dateFormat = DateTimeFormatter.ofPattern("d.MM", polish)

    fun jumpDay(items: List<TimelineItem>, focus: HistoryFocus, delta: Int, now: Long): HistoryFocus {
        if (items.isEmpty() || delta == 0) return focus
        val current = Instant.ofEpochMilli(items[focus.index(items)].start).atZone(zone)
        val days = items.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.distinct().sorted()
        val requested = current.toLocalDate().plusDays(delta.toLong()).coerceIn(days.first(), days.last())
        val day = if (delta > 0) days.firstOrNull { it >= requested } ?: days.last()
            else days.lastOrNull { it <= requested } ?: days.first()
        if (day == current.toLocalDate()) return focus
        val time = day.atTime(current.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
            .coerceIn(items.first().start, maxOf(now, items.last().start))
        val candidates = items.indices.filter {
            Instant.ofEpochMilli(items[it].start).atZone(zone).toLocalDate() == day
        }
        val index = candidates.lastOrNull { items[it].start <= time }
            ?: candidates.firstOrNull() ?: ChannelTimeline.historyFocusIndex(items, time)
        return HistoryFocus(items[index].start, items[index].live)
    }

    fun select(item: TimelineItem, showFuture: (Long) -> Unit, play: (TimelineItem) -> Unit) {
        if (item.future) showFuture(item.start) else play(item)
    }

    fun dayLabel(time: Long, now: Long, todayLabel: String, yesterdayLabel: String, tomorrowLabel: String): String {
        val day = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> todayLabel
            today.minusDays(1) -> yesterdayLabel
            today.plusDays(1) -> tomorrowLabel
            else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, polish).removeSuffix(".")
                .replaceFirstChar { it.titlecase(polish) } + " " + dateFormat.format(day)
        }
    }
}
