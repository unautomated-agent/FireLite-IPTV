package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeTest {
    private val now = 1_800_000_000_000L
    private val dvr = PlaybackSnapshot(true, 60_000, 60_000, 120_000)

    @Test fun liveOffsetIdentifiesTheFrameRatherThanThePlaybackSessionStart() {
        assertEquals(now - 60_000, PlaybackTime.watched(now, dvr, null, now - 1_000))
        assertEquals(now, PlaybackTime.watched(now, dvr.copy(liveOffsetMs = -1), null, 0))
    }

    @Test fun archivePositionAdvancesWithoutSubtractingItsDelayTwice() {
        assertEquals(now - 540_000, PlaybackTime.watched(now, dvr, now - 600_000, 0))
        assertEquals(now - 540_000, PlaybackTime.watched(now + 30_000, dvr, now - 600_000, 0))
    }

    @Test fun serverWindowTimestampIsAuthoritativeIncludingSlidingArchiveWindows() {
        assertEquals(now - 30_000, PlaybackTime.watched(now, dvr.copy(windowStartMs = now - 90_000), now - 600_000, 0))
    }

    @Test fun fixedStreamFallbackUsesPositionSoPausingDoesNotAdvanceTheFrameClock() {
        val vod = dvr.copy(liveOffsetMs = null)
        assertEquals(now - 60_000, PlaybackTime.watched(now + 10_000, vod, null, now - 120_000))
    }

    @Test fun programmeProgressIsRelativeClampedAndSafeForInvalidIntervals() {
        assertEquals(0.25f, PlaybackTime.progress(now - 45_000, now - 60_000, now), 0f)
        assertEquals(0f, PlaybackTime.progress(now - 70_000, now - 60_000, now), 0f)
        assertEquals(1f, PlaybackTime.progress(now + 1, now - 60_000, now), 0f)
        assertEquals(0f, PlaybackTime.progress(now, now, now), 0f)
        assertEquals(0f, PlaybackTime.progress(now, now, now - 1), 0f)
    }

    @Test fun crossingProgrammeBoundaryResetsProgressToTheNewProgramme() {
        assertEquals(1f, PlaybackTime.progress(now, now - 60_000, now), 0f)
        assertEquals(0f, PlaybackTime.progress(now, now, now + 60_000), 0f)
    }

    private fun plan(target: Long, snapshot: PlaybackSnapshot = dvr, archive: Long? = null,
        days: Int = 1, supported: Boolean = true) =
        PlaybackTime.seekPlan(target, now, snapshot, archive, now - 120_000, days, supported)

    @Test fun seeksInsideTheServerWindowWithoutOpeningAnArchive() {
        assertEquals(SeekPlan.InWindow(30_000), plan(now - 90_000))
        assertEquals(SeekPlan.InWindow(0), plan(now - 120_000))
    }

    @Test fun goingBeforeTheWindowSwitchesToTheRequestedAbsoluteArchiveTime() {
        assertEquals(SeekPlan.Archive(now - 120_001), plan(now - 120_001))
        assertEquals(SeekPlan.Archive(now - 30_000), plan(now - 30_000, PlaybackSnapshot.EMPTY))
    }

    @Test fun unavailableOrExpiredArchivesDoNotClampSilentlyToTheWindowStart() {
        assertEquals(SeekPlan.Unavailable, plan(now - 120_001, supported = false))
        assertEquals(SeekPlan.Unavailable, plan(now - 120_001, days = 0))
        assertEquals(SeekPlan.Unavailable, plan(now - 86_400_001))
        assertEquals(SeekPlan.Unavailable, plan(now - 30_000, PlaybackSnapshot.EMPTY, supported = false))
    }

    @Test fun archiveSeekableAndNonSeekableStreamsUseTheSameAbsoluteTarget() {
        val target = now - 540_000
        assertEquals(SeekPlan.InWindow(60_000), plan(target, archive = now - 600_000))
        assertEquals(SeekPlan.Archive(target), plan(target, dvr.copy(seekable = false), now - 600_000))
        assertEquals(SeekPlan.Archive(now - 600_001), plan(now - 600_001, archive = now - 600_000))
    }

    @Test fun movingForwardPastTheLiveEdgeReturnsToLive() {
        assertEquals(SeekPlan.Live, plan(now - 1_000))
        assertEquals(SeekPlan.Live, plan(now + 30_000, archive = now - 600_000))
        assertEquals(SeekPlan.Unavailable, plan(now, PlaybackSnapshot.EMPTY, supported = false))
    }
}
