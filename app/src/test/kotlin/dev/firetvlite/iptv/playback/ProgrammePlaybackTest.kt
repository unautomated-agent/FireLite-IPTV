package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ProgrammePlaybackTest {
    private val now = 1_800_000_000_000L
    private val bufferedFrame = PlaybackFrame(now, now - 30_000,
        PlaybackSnapshot(true, 30_000, positionMs = 90_000, durationMs = 120_000))
    private fun programme(start: Long, stop: Long) = ProgrammeEntity(
        sourceId = 1, generation = "test", epgKey = "channel", start = start, stop = stop,
        title = "Programme", description = null, category = null,
    )

    @Test fun currentHistoryProgrammeWithoutCatchupRestartsAtItsBufferedStart() {
        val current = programme(now - 60_000, now + 60_000)
        val item = TimelineItem(current.start, current.stop, current)
        assertEquals(TimelineAction.Live, ChannelTimeline.selection(item, now, 0, false))
        assertEquals(60_000L, ProgrammePlayback.bufferedStart(item.programme, bufferedFrame, true, false))
        assertEquals(119_000L,
            ProgrammePlayback.bufferedStart(programme(now - 1_000, now + 60_000), bufferedFrame, true, false))
    }

    @Test fun bufferBoundariesAreCheckedWithoutClampingAnExpiredProgrammeStart() {
        assertEquals(0L,
            ProgrammePlayback.bufferedStart(programme(now - 120_000, now + 1), bufferedFrame, true, false))
        assertNull(ProgrammePlayback.bufferedStart(programme(now - 120_001, now + 1), bufferedFrame, true, false))
        assertNull(ProgrammePlayback.bufferedStart(programme(now, now + 60_000), bufferedFrame, true, false))
    }

    @Test fun explicitLiveOtherChannelsArchiveAndUnseekableWindowsDoNotUseBufferedRestart() {
        val current = programme(now - 60_000, now + 60_000)
        assertNull(ProgrammePlayback.bufferedStart(null, bufferedFrame, true, false))
        assertNull(ProgrammePlayback.bufferedStart(current, bufferedFrame, false, false))
        assertNull(ProgrammePlayback.bufferedStart(current, bufferedFrame, true, true))
        assertNull(ProgrammePlayback.bufferedStart(current,
            bufferedFrame.copy(snapshot = bufferedFrame.snapshot.copy(seekable = false)), true, false))
        assertNull(ProgrammePlayback.bufferedStart(current,
            bufferedFrame.copy(snapshot = bufferedFrame.snapshot.copy(durationMs = null)), true, false))
        assertNull(ProgrammePlayback.bufferedStart(programme(now + 1, now + 60_000), bufferedFrame, true, false))
        assertNull(ProgrammePlayback.bufferedStart(programme(now - 60_000, now), bufferedFrame, true, false))
    }

    @Test fun guideAndHistoryShareTheSameChannelSeekPathWithoutDirectStreamRequests() {
        val guide = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/LiveTvScreen.kt").readText()
        assertTrue(guide.contains("player.openLiveProgramme(it, index, selected, row.channel, programme)"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val liveSelection = model.substringAfter("TimelineAction.Live -> {").substringBefore("TimelineAction.Past ->")
        assertTrue(liveSelection.contains("openLiveProgramme(target.zap, target.index, target.group, target.channel,"))
        assertTrue(liveSelection.contains("item.programme)"))
        assertFalse(liveSelection.contains("if (item.live) null else item.programme"))
        val method = model.substringAfter("fun openLiveProgramme(").substringBefore("fun playTimeline(")
        assertTrue(method.contains("activeChannel?.streamUrl == channel.streamUrl && archiveStartMs == null"))
        assertTrue(method.contains("if (position != null) playback.seekTo(position) else playback.seekToLiveEdge()"))
        assertFalse(method.contains("playback.play("))
        assertFalse(method.contains("openArchive("))
        assertFalse(method.contains("prepare("))
        assertTrue(model.contains("else if (!sameChannel || archiveStartMs != null || status.value is PlaybackStatus.Failed) startLive(first)"))
    }

    @Test fun retainedPastAndCurrentlyAiringProgrammesSelectArchive() {
        for (programme in listOf(programme(now - 60_000, now), programme(now - 60_000, now + 60_000),
            programme(now, now + 60_000))) {
            assertEquals(TimelineAction.Archive, ProgrammePlayback.action(programme, now, 1, true))
        }
    }

    @Test fun retentionUsesOriginalStartAndIncludesTheExactBoundary() {
        val limit = now - ChannelTimeline.DAY
        assertEquals(TimelineAction.Archive, ProgrammePlayback.action(programme(limit, now - 1), now, 1, true))
        assertEquals(TimelineAction.Past, ProgrammePlayback.action(programme(limit - 1, now - 1), now, 1, true))
        assertEquals(TimelineAction.Live, ProgrammePlayback.action(programme(limit - 1, now + 1), now, 1, true))
    }

    @Test fun currentProgrammeWithoutUsableArchiveSelectsLive() {
        val current = programme(now - 60_000, now + 60_000)
        for ((days, supported) in listOf(0 to true, -1 to true, 1 to false)) {
            assertEquals(TimelineAction.Live, ProgrammePlayback.action(current, now, days, supported))
        }
    }

    @Test fun unsupportedPastProgrammesRetainThePastFallback() {
        val past = programme(now - 60_000, now)
        assertEquals(TimelineAction.Past, ProgrammePlayback.action(past, now, 1, false))
        assertEquals(TimelineAction.Past, ProgrammePlayback.action(past, now, 0, true))
    }

    @Test fun futureAndMalformedProgrammesNeverPlay() {
        for (programme in listOf(programme(now + 1, now + 60_000), programme(now, now), programme(now, now - 1))) {
            assertEquals(TimelineAction.None, ProgrammePlayback.action(programme, now, 1, true))
            assertEquals(TimelineAction.None, ProgrammePlayback.action(programme, now, 0, false))
        }
        assertEquals(TimelineAction.Live, ProgrammePlayback.action(null, now, 0, false))
    }

    @Test fun historyCurrentProgrammeKeepsArchiveSelectionWhenMergedIntoTheLiveRow() {
        val current = programme(now - 60_000, now + 60_000)
        val item = TimelineItem(current.start, current.stop, current, current = true)
        assertEquals(TimelineAction.Archive, ChannelTimeline.selection(item, now, 1, true))
        assertEquals(TimelineAction.Archive, ChannelTimeline.selection(item.copy(current = false), now, 1, true))
        assertEquals(TimelineAction.Archive, ChannelTimeline.selection(item.copy(live = true), now, 1, true))
        assertEquals(TimelineAction.Live, ChannelTimeline.selection(item.copy(live = true, programme = null), now, 1, true))
        assertEquals(TimelineAction.Live, ChannelTimeline.selection(item, now, 0, false))
    }

    @Test fun clippedHistoryRowsCannotPlayAPartiallyExpiredProgrammeFromAMidpoint() {
        val limit = now - ChannelTimeline.DAY
        val original = programme(limit - 60_000, limit + 60_000)
        val clipped = TimelineItem(limit, original.stop, original)
        assertEquals(TimelineAction.Past, ChannelTimeline.selection(clipped, now, 1, true))
        val retained = programme(limit, limit + 60_000)
        assertEquals(TimelineAction.Archive,
            ChannelTimeline.selection(TimelineItem(limit + 1, retained.stop, retained), now, 1, true))
    }

    @Test fun historyFutureRowsAndUnlabelledBufferSlotsKeepTheirDecisions() {
        val future = programme(now + 60_000, now + 120_000)
        assertEquals(TimelineAction.None,
            ChannelTimeline.selection(TimelineItem(future.start, future.stop, future, future = true), now, 1, true))
        assertEquals(TimelineAction.None,
            ChannelTimeline.selection(TimelineItem(now + 60_000, now + 120_000, future = true), now, 1, true))
        assertEquals(TimelineAction.Past,
            ChannelTimeline.selection(TimelineItem(now - 60_000, now), now, 0, false))
    }

    @Test fun bothSelectionEntrypointsUseTheExistingArchivePathWithTheOriginalStart() {
        val guide = File("src/main/kotlin/dev/firetvlite/iptv/ui/screens/LiveTvScreen.kt").readText()
        assertTrue(guide.contains("player.openArchive(it, index, selected, programme!!.start)"))
        val model = File("src/main/kotlin/dev/firetvlite/iptv/ui/viewmodel/PlayerViewModel.kt").readText()
        val selection = model.substringAfter("fun playTimeline(").substringBefore("    private fun restoreChannel(")
        val archive = selection.substringAfter("TimelineAction.Archive -> {").substringBefore("TimelineAction.Live ->")
        assertTrue(archive.contains("closeTimeline()"))
        assertTrue(archive.contains("openArchive(target.zap, target.index, target.group, item.programme!!.start)"))
        assertFalse(selection.contains("playback.play("))
        assertFalse(selection.contains("prepare("))
        val openArchive = model.substringAfter("fun openArchive(").substringBefore("    private fun select(")
        assertTrue(openArchive.contains("select(zap, startIndex, group, PlayerMode.Fullscreen, time)"))
        assertTrue(model.contains("applyCatchup(first, archiveTime, restartIfActive = true)"))
        assertTrue(model.contains("restartIfActive: Boolean = false"))
        assertTrue(model.contains("if (restartIfActive) playback.playArchiveFromStart(request) else playback.play(request)"))
    }
}
