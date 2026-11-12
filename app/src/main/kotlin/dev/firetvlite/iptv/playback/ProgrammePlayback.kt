package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ProgrammeEntity

object ProgrammePlayback {
    fun bufferedStart(programme: ProgrammeEntity?, frame: PlaybackFrame, sameLiveChannel: Boolean,
        supportsCatchup: Boolean): Long? {
        if (!sameLiveChannel || supportsCatchup || programme == null || !frame.snapshot.seekable) return null
        if (programme.start > frame.nowMs || programme.stop <= frame.nowMs) return null
        val duration = frame.snapshot.durationMs ?: return null
        val position = programme.start - (frame.watchedMs - frame.snapshot.positionMs)
        return position.takeIf { it >= 0 && it < duration }
    }

    fun action(programme: ProgrammeEntity?, now: Long, catchupDays: Int, supportsCatchup: Boolean): TimelineAction = when {
        programme == null -> TimelineAction.Live
        programme.stop <= programme.start || programme.start > now -> TimelineAction.None
        supportsCatchup && catchupDays > 0 && programme.start >= now - catchupDays.toLong() * ChannelTimeline.DAY -> TimelineAction.Archive
        programme.stop > now -> TimelineAction.Live
        else -> TimelineAction.Past
    }
}
