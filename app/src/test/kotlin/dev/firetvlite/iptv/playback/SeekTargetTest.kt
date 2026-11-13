package dev.firetvlite.iptv.playback

import org.junit.Assert.*
import org.junit.Test

class SeekTargetTest {
    private val now = 1_800_000_000_000L
    private val frame = PlaybackFrame(now, now - 20_000, PlaybackSnapshot.EMPTY.copy(positionMs = 30_000,
        durationMs = 50_000, seekable = true, isLive = true))
    private val fallbackStart = now - 50_000
    @Test fun forwardCrossingShowsCommittedDestinationWhileOnlyTheMarkerClampsToProgrammeEnd() {
        val plan = PlaybackTime.relativeSeekPlan(15_000, frame, null, fallbackStart, 0, false)
        assertEquals(SeekPlan.InWindow(45_000), plan)
        val target = SeekTarget.time(frame, 15_000, null, fallbackStart, 0, false)!!
        assertEquals(now - 5000, target)
        assertEquals(1f, PlaybackTime.progress(target, now - 30_000, now - 15_000))
    }
    @Test fun backwardCrossingShowsCommittedDestinationWhileOnlyTheMarkerClampsToProgrammeStart() {
        assertEquals(SeekPlan.InWindow(10_000), PlaybackTime.relativeSeekPlan(-20_000, frame, null, fallbackStart, 0, false))
        val target = SeekTarget.time(frame, -20_000, null, fallbackStart, 0, false)!!
        assertEquals(now - 40_000, target)
        assertEquals(0f, PlaybackTime.progress(target, now - 30_000, now - 15_000))
    }
    @Test fun archiveAndLiveDestinationsUseTheSamePlansAsCommit() {
        assertEquals(now - 120_000, SeekTarget.time(frame, -100_000, null, fallbackStart, 1, true))
        assertEquals(now, SeekTarget.time(frame, 100_000, null, fallbackStart, 0, false))
        val archive = frame.copy(snapshot = frame.snapshot.copy(isLive = false))
        assertEquals(now - 5000, SeekTarget.time(archive, 15_000, fallbackStart, fallbackStart, 1, true))
        val nearLive = archive.copy(watchedMs = now - 2000, snapshot = archive.snapshot.copy(positionMs = 48_000))
        assertEquals(now, SeekTarget.time(nearLive, 1000, fallbackStart, fallbackStart, 1, true))
    }
    @Test fun pendingArchiveTargetIsTheBaseForFurtherSeekFeedback() {
        assertEquals(now - 90_000, SeekTarget.time(frame, 10_000, null, fallbackStart, 1, true, now - 100_000))
    }
    @Test fun unavailableSeeksAndUnknownFramesDoNotShowAnInventedClampedDestination() {
        assertNull(SeekTarget.time(frame, -100_000, null, fallbackStart, 0, false))
        assertNull(SeekTarget.time(frame, -2 * 86_400_000L, null, fallbackStart, 1, true))
        assertNull(SeekTarget.time(PlaybackFrame(), 10, null, 0, 0, false))
        assertNull(SeekTarget.time(frame.copy(snapshot = PlaybackSnapshot.EMPTY), 10, null, fallbackStart, 0, false))
        assertEquals(now - 20_000, frame.watchedMs)
    }
}
