package dev.firetvlite.iptv.playback

internal object SeekTarget {
    fun time(frame: PlaybackFrame, offset: Long, archiveStart: Long?, fallbackStart: Long,
        catchupDays: Int, catchup: Boolean, pendingTime: Long? = null): Long? {
        if (frame.nowMs <= 0 || frame.watchedMs <= 0) return null
        val plan = if (pendingTime == null) PlaybackTime.relativeSeekPlan(offset, frame, archiveStart, fallbackStart,
            catchupDays, catchup) else PlaybackTime.seekPlan(pendingTime + offset, frame.nowMs, frame.snapshot,
            archiveStart, fallbackStart, catchupDays, catchup)
        return when (plan) {
            is SeekPlan.InWindow -> frame.watchedMs - frame.snapshot.positionMs + plan.positionMs
            is SeekPlan.Archive -> plan.timeMs
            SeekPlan.Live -> frame.nowMs
            SeekPlan.Unavailable -> null
        }
    }
}
