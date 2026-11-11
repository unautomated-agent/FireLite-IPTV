package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.data.db.ProgrammeEntity

data class GuideBounds(val earliest: Long, val latest: Long)
data class GuideCell(val start: Long, val stop: Long, val programme: ProgrammeEntity?)
enum class GuideAction { Live, Archive, Details }

object GuideTimeline {
    const val HOUR = 3_600_000L
    const val WINDOW = 150 * 60_000L
    const val DAY = 24 * HOUR

    fun bounds(now: Long, catchupDays: Int, lastProgramme: Long?): GuideBounds = GuideBounds(
        now - catchupDays.coerceIn(0, 7) * DAY,
        (lastProgramme ?: (now + WINDOW)).coerceIn(now, now + 3 * DAY),
    )

    fun windowStart(requested: Long, bounds: GuideBounds): Long =
        requested.coerceIn(bounds.earliest, (bounds.latest - WINDOW).coerceAtLeast(bounds.earliest))

    fun cells(programmes: List<ProgrammeEntity>, start: Long): List<GuideCell> {
        val end = start + WINDOW
        val result = ArrayList<GuideCell>()
        var cursor = start
        for (programme in programmes.sortedBy { it.start }) {
            val a = programme.start.coerceAtLeast(cursor)
            val b = programme.stop.coerceAtMost(end)
            if (a >= b) continue
            if (a > cursor) result.add(GuideCell(cursor, a, null))
            result.add(GuideCell(a, b, programme))
            cursor = b
        }
        if (cursor < end) result.add(GuideCell(cursor, end, null))
        return result
    }

    fun focusIndex(cells: List<GuideCell>, time: Long): Int =
        cells.indexOfFirst { time >= it.start && time < it.stop }.let {
            if (it >= 0) it else if (cells.isEmpty() || time < cells.first().start) 0 else cells.lastIndex
        }

    fun fraction(time: Long, windowStart: Long): Float =
        ((time - windowStart).toDouble() / WINDOW).toFloat().coerceIn(0f, 1f)

    fun adjacentTime(cell: GuideCell, forward: Boolean): Long =
        if (forward) cell.programme?.stop ?: cell.stop else (cell.programme?.start ?: cell.start) - 1

    fun reveal(start: Long, target: Long, bounds: GuideBounds): Long {
        val proposed = start + if (target < start) -HOUR else HOUR
        val requested = when {
            target < proposed -> target
            target >= proposed + WINDOW -> target - WINDOW + 1
            else -> proposed
        }
        return windowStart(requested, bounds)
    }

    fun action(programme: ProgrammeEntity?, now: Long, catchupDays: Int, supportsCatchup: Boolean,
        focusedTime: Long = now, hasGuide: Boolean = programme != null): GuideAction = when {
        programme == null && hasGuide && focusedTime > now -> GuideAction.Details
        programme == null -> GuideAction.Live
        programme.start <= now && programme.stop > now -> GuideAction.Live
        programme.stop <= now && supportsCatchup && catchupDays > 0 && programme.start >= now - catchupDays * DAY -> GuideAction.Archive
        else -> GuideAction.Details
    }
}
