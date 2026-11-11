package dev.firetvlite.iptv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakePolicyTest {
    @Test fun visiblePlayingConnectingAndBufferingModesKeepTheScreenAwake() {
        for (mode in listOf(PlayerMode.Preview, PlayerMode.Fullscreen, PlayerMode.Overlay)) {
            for (status in listOf(PlaybackStatus.Playing, PlaybackStatus.Buffering, PlaybackStatus.Connecting)) {
                assertTrue(ScreenAwakePolicy.keepAwake(mode, status, true))
                assertFalse(ScreenAwakePolicy.keepAwake(mode, status, false))
            }
        }
    }

    @Test fun closedPausedFailedIdleAndEndedPlaybackReleaseTheScreen() {
        assertFalse(ScreenAwakePolicy.keepAwake(PlayerMode.Closed, PlaybackStatus.Playing, true))
        for (status in listOf(PlaybackStatus.Paused, PlaybackStatus.Idle, PlaybackStatus.Ended, PlaybackStatus.Failed("HTTP"))) {
            assertFalse(ScreenAwakePolicy.keepAwake(PlayerMode.Fullscreen, status, true))
        }
    }
}
