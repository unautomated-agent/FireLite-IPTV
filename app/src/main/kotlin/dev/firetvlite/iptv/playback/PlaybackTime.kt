package dev.firetvlite.iptv.playback

sealed interface SeekPlan {
    data class InWindow(val positionMs: Long) : SeekPlan
    data class Archive(val timeMs: Long) : SeekPlan
    data object Live : SeekPlan
    data object Unavailable : SeekPlan
}

data class PlaybackFrame(
    val nowMs: Long = 0,
    val watchedMs: Long = 0,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot.EMPTY,
)

object PlaybackTime {
    const val LIVE_TOLERANCE_MS = 1_500L

    fun archiveContinuation(frame: PlaybackFrame, archiveStart: Long): SeekPlan =
        if (frame.watchedMs <= archiveStart || frame.watchedMs >= frame.nowMs - LIVE_TOLERANCE_MS) SeekPlan.Live
        else SeekPlan.Archive(frame.watchedMs)

    fun watched(now: Long, snapshot: PlaybackSnapshot, archiveStart: Long?, fallbackStart: Long): Long = when {
        snapshot.windowStartMs != null -> snapshot.windowStartMs + snapshot.positionMs
        archiveStart != null -> archiveStart + snapshot.positionMs
        snapshot.liveOffsetMs != null -> now - snapshot.liveOffsetMs.coerceAtLeast(0)
        else -> fallbackStart + snapshot.positionMs
    }

    fun progress(time: Long, start: Long, stop: Long): Float =
        if (stop <= start) 0f else ((time - start).toDouble() / (stop - start)).toFloat().coerceIn(0f, 1f)

    fun seekPlan(
        target: Long,
        now: Long,
        snapshot: PlaybackSnapshot,
        archiveStart: Long?,
        fallbackStart: Long,
        catchupDays: Int,
        supportsCatchup: Boolean,
    ): SeekPlan {
        if (target >= now - LIVE_TOLERANCE_MS) {
            return if (archiveStart != null || snapshot.seekable) SeekPlan.Live else SeekPlan.Unavailable
        }
        val watched = watched(now, snapshot, archiveStart, fallbackStart)
        val windowStart = watched - snapshot.positionMs
        val duration = snapshot.durationMs
        if (snapshot.seekable && duration != null && target >= windowStart && target < windowStart + duration) {
            return SeekPlan.InWindow(target - windowStart)
        }
        return if (supportsCatchup && catchupDays > 0 && target >= now - catchupDays * 86_400_000L) {
            SeekPlan.Archive(target)
        } else SeekPlan.Unavailable
    }
}
