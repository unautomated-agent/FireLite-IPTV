package dev.firetvlite.iptv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSourcePolicyTest {
    @Test fun switchingOrDeletingThePlayingSourceClosesItsSession() {
        assertTrue(PlaybackSourcePolicy.shouldClose(1, 2))
        assertTrue(PlaybackSourcePolicy.shouldClose(1, null))
        assertFalse(PlaybackSourcePolicy.shouldClose(1, 1))
        assertFalse(PlaybackSourcePolicy.shouldClose(null, 2))
        assertFalse(PlaybackSourcePolicy.shouldClose(null, null))
    }
}
