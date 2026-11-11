package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerModeTest {
    @Test fun nonLiveTabsClosePlaybackRatherThanLeavingAnInvisiblePreview() {
        assertEquals(PlayerMode.Closed, PlayerMode.Fullscreen.afterBack(previewAllowed = false))
        assertEquals(PlayerMode.Fullscreen, PlayerMode.Overlay.afterBack(previewAllowed = false))
        assertEquals(PlayerMode.Preview, PlayerMode.Fullscreen.afterBack(previewAllowed = true))
    }
    @Test fun onlyExplicitFullscreenPlaybackRecordsWatchHistory() {
        assertFalse(PlayerMode.Closed.recordsWatch)
        assertFalse(PlayerMode.Preview.recordsWatch)
        assertFalse(PlayerMode.Overlay.recordsWatch)
        assertTrue(PlayerMode.Fullscreen.recordsWatch)
    }
    @Test fun backKeepsTheStreamAndClosesOnlyTheTopLayer() {
        assertEquals(PlayerMode.Fullscreen, PlayerMode.Overlay.afterBack())
        assertEquals(PlayerMode.Preview, PlayerMode.Fullscreen.afterBack())
        assertEquals(PlayerMode.Preview, PlayerMode.Preview.afterBack())
        assertEquals(PlayerMode.Closed, PlayerMode.Closed.afterBack())
    }

    @Test fun browsingCannotReplaceFullscreenOrOverlayPlayback() {
        assertTrue(PlayerMode.Closed.acceptsPreview)
        assertTrue(PlayerMode.Preview.acceptsPreview)
        assertFalse(PlayerMode.Fullscreen.acceptsPreview)
        assertFalse(PlayerMode.Overlay.acceptsPreview)
        assertTrue(PlayerMode.Overlay.fullFrame)
        assertTrue(PlayerMode.Fullscreen.fullFrame)
        assertFalse(PlayerMode.Preview.fullFrame)
        assertFalse(PlayerMode.Closed.fullFrame)
    }
}
