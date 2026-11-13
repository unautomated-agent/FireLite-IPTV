package dev.firetvlite.iptv.playback

internal object SeekTarget {
    fun time(frame: PlaybackFrame, start: Long, stop: Long, offset: Long,
        catchupDays: Int, catchup: Boolean): Long? {
        if (stop <= start || frame.nowMs <= 0 || frame.watchedMs <= 0) return null
        val snapshot = frame.snapshot
        val bufferStart = (frame.watchedMs - snapshot.positionMs).takeIf {
            snapshot.seekable && (snapshot.durationMs ?: 0) > 0
        }
        val archiveStart = (frame.nowMs - catchupDays.toLong() * DAY_MS).takeIf { catchup && catchupDays > 0 }
        val lower = listOfNotNull(bufferStart, archiveStart).minOrNull()?.coerceAtLeast(start) ?: return null
        val upper = minOf(stop, frame.nowMs, if (archiveStart != null) frame.nowMs
            else bufferStart!! + snapshot.durationMs!!)
        if (upper < lower) return null
        return (frame.watchedMs.toDouble() + offset.toDouble()).coerceIn(lower.toDouble(), upper.toDouble()).toLong()
    }

    private const val DAY_MS = 86_400_000L
}
