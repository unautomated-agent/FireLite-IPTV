package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ProgrammeEntity

object ProgrammePlayback {
    fun action(programme: ProgrammeEntity?, now: Long, catchupDays: Int, supportsCatchup: Boolean): TimelineAction = when {
        programme == null -> TimelineAction.Live
        programme.stop <= programme.start || programme.start > now -> TimelineAction.None
        supportsCatchup && catchupDays > 0 && programme.start >= now - catchupDays.toLong() * ChannelTimeline.DAY -> TimelineAction.Archive
        programme.stop > now -> TimelineAction.Live
        else -> TimelineAction.Past
    }
}
