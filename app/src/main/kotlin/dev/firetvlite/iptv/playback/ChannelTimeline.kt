package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ProgrammeEntity

data class TimelineItem(
    val start: Long,
    val stop: Long,
    val programme: ProgrammeEntity? = null,
    val current: Boolean = false,
    val future: Boolean = false,
    val live: Boolean = false,
)

enum class TimelineAction { Live, Past, None }

object ChannelTimeline {
    const val HOUR = 3_600_000L
    const val DAY = 24 * HOUR

    fun earliest(now: Long, catchupDays: Int, supported: Boolean, liveWindowStart: Long?): Long? =
        if (supported && catchupDays > 0) now - catchupDays.toLong() * DAY
        else liveWindowStart?.takeIf { it < now }

    fun items(programmes: List<ProgrammeEntity>, now: Long, earliest: Long?): List<TimelineItem> = buildList {
        val ordered = programmes.filter { it.stop > it.start }.sortedBy { it.start }
        if (earliest != null && earliest < now) {
            var cursor: Long = earliest
            fun gap(until: Long) {
                while (cursor < until) {
                    val stop = minOf(until, Math.floorDiv(cursor, HOUR) * HOUR + HOUR)
                    add(TimelineItem(cursor, stop))
                    cursor = stop
                }
            }
            for (programme in ordered) {
                if (programme.start > now || cursor >= now) break
                if (programme.stop <= cursor) continue
                gap(minOf(programme.start, now))
                add(TimelineItem(maxOf(cursor, programme.start), programme.stop, programme,
                    current = programme.start <= now && programme.stop > now))
                cursor = minOf(programme.stop, now)
            }
            gap(now)
        } else {
            ordered.firstOrNull { it.start <= now && it.stop > now }?.let {
                add(TimelineItem(it.start, it.stop, it, current = true))
            }
        }
        ordered.firstOrNull { it.start > now }?.let {
            add(TimelineItem(it.start, it.stop, it, future = true))
        }
        add(TimelineItem(now, Long.MAX_VALUE, live = true))
    }

    fun focusIndex(items: List<TimelineItem>, time: Long): Int =
        items.indexOfFirst { !it.live && time >= it.start && time < it.stop }
            .takeIf { it >= 0 } ?: if (items.isNotEmpty() && time < items.first().start) 0 else items.lastIndex

    fun move(index: Int, delta: Int, count: Int): Int =
        if (count == 0) 0 else (index + delta).coerceIn(0, count - 1)

    fun action(item: TimelineItem): TimelineAction = when {
        item.future -> TimelineAction.None
        item.live || item.current -> TimelineAction.Live
        else -> TimelineAction.Past
    }
}
