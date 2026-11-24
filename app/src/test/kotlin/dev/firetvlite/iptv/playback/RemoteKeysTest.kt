package dev.firetvlite.iptv.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteKeysTest {
    @Test fun learnedButtonsActLikeMenuAndHome() {
        val map = RemoteKeyMap(menu = KeyEvent.KEYCODE_F1, home = KeyEvent.KEYCODE_ESCAPE)
        assertEquals(RemoteAlias.Menu, map.alias(KeyEvent.KEYCODE_F1))
        assertEquals(RemoteAlias.Home, map.alias(KeyEvent.KEYCODE_ESCAPE))
        assertNull(map.alias(KeyEvent.KEYCODE_F2))
    }

    @Test fun tvOptionsButtonIsMenuUnlessLearnedAsHome() {
        assertEquals(RemoteAlias.Menu, RemoteKeyMap().alias(KeyEvent.KEYCODE_TV_CONTENTS_MENU))
        assertEquals(RemoteAlias.Home,
            RemoteKeyMap(home = KeyEvent.KEYCODE_TV_CONTENTS_MENU).alias(KeyEvent.KEYCODE_TV_CONTENTS_MENU))
    }

    @Test fun fireTvButtonsKeepTheirOwnFunction() {
        val map = RemoteKeyMap(menu = KeyEvent.KEYCODE_BACK, home = KeyEvent.KEYCODE_DPAD_CENTER)
        listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_UNKNOWN).forEach { assertNull(map.alias(it)) }
    }

    @Test fun redButtonIsKeptForSettings() {
        assertEquals(KeyEvent.KEYCODE_PROG_RED, RemoteKeyMap.SETTINGS_KEY)
        assertNull(RemoteKeyMap(menu = KeyEvent.KEYCODE_PROG_RED).alias(KeyEvent.KEYCODE_PROG_RED))
    }

    @Test fun oneButtonHasOneFunction() {
        val map = RemoteKeyMap(menu = KeyEvent.KEYCODE_F1).with(RemoteAlias.Home, KeyEvent.KEYCODE_F1)
        assertEquals(RemoteKeyMap(menu = 0, home = KeyEvent.KEYCODE_F1), map)
    }

    @Test fun learningSavesTheNextFreeButtonOnly() {
        var saved = RemoteKeyMap()
        val learning = RemoteKeyLearning({ saved }) { saved = it }
        assertNull(learning.pressed(KeyEvent.KEYCODE_F1))

        learning.start(RemoteAlias.Home)
        assertEquals(RemoteLearnResult.Saved, learning.pressed(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(KeyEvent.KEYCODE_ESCAPE, saved.home)
        assertNull(learning.pressed(KeyEvent.KEYCODE_F1))

        learning.start(RemoteAlias.Menu)
        assertEquals(RemoteLearnResult.Used, learning.pressed(KeyEvent.KEYCODE_DPAD_LEFT))
        learning.start(RemoteAlias.Menu)
        assertEquals(RemoteLearnResult.Cancelled, learning.pressed(KeyEvent.KEYCODE_BACK))
        learning.start(RemoteAlias.Menu)
        assertEquals(RemoteLearnResult.Timeout, learning.timeout())
        assertEquals(RemoteKeyMap(home = KeyEvent.KEYCODE_ESCAPE), saved)
    }
}
