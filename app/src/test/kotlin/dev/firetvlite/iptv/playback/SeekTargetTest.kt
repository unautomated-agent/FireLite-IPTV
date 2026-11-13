package dev.firetvlite.iptv.playback

import org.junit.Assert.*
import org.junit.Test

class SeekTargetTest {
    private val frame = PlaybackFrame(100_000, 80_000, PlaybackSnapshot.EMPTY.copy(positionMs = 30_000,
        durationMs = 50_000, seekable = true))
    @Test fun targetClampsToProgrammeBufferAndLiveEdgeWithoutChangingFrame() {
        assertEquals(90_000L, SeekTarget.time(frame, 40_000, 120_000, 10_000, 0, false))
        assertEquals(50_000L, SeekTarget.time(frame, 40_000, 120_000, -100_000, 0, false))
        assertEquals(100_000L, SeekTarget.time(frame, 40_000, 120_000, Long.MAX_VALUE, 0, false))
        assertEquals(85_000L, SeekTarget.time(frame, 40_000, 85_000, 100_000, 0, false))
        assertEquals(80_000L, frame.watchedMs)
    }
    @Test fun catchupAllowsProgrammeStartOutsideBufferAndUnknownBoundsProduceNoMarker() {
        assertEquals(40_000L, SeekTarget.time(frame, 40_000, 120_000, -100_000, 1, true))
        assertNull(SeekTarget.time(PlaybackFrame(), 0, 1, 10, 0, false))
        assertNull(SeekTarget.time(frame, 100, 100, 10, 1, true))
        assertNull(SeekTarget.time(frame.copy(snapshot = PlaybackSnapshot.EMPTY), 0, 120_000, 10, 0, false))
    }
}
