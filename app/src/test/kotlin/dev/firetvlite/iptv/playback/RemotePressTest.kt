package dev.firetvlite.iptv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePressTest {
    @Test fun shortPressActsOnlyOnRelease() {
        val press = RemotePress()
        press.begin()
        assertTrue(press.release())
        assertFalse(press.release())
    }

    @Test fun longPressActsOnceAndNeverAlsoActsAsShortPress() {
        val press = RemotePress()
        press.begin()
        assertTrue(press.longPress())
        assertFalse(press.longPress())
        assertFalse(press.release())
        assertFalse(press.longPress())
        press.begin()
        assertTrue(press.release())
    }

    @Test fun releaseFromADismissedHistoryDoesNotOpenChannels() {
        val press = RemotePress()
        assertFalse(press.release())
        assertFalse(press.longPress())
    }
}
