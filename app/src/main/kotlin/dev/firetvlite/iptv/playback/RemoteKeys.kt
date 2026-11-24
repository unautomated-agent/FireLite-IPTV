package dev.firetvlite.iptv.playback

import android.view.KeyEvent

enum class RemoteAlias { Menu, Home }

/**
 * Buttons of another remote that act like Fire TV's Menu and Home, for example Tools and Exit
 * on a TV remote whose presses the TV passes on over HDMI. A button the app already uses cannot be taken.
 */
data class RemoteKeyMap(val menu: Int = 0, val home: Int = 0) {
    fun alias(keyCode: Int): RemoteAlias? = when {
        !assignable(keyCode) -> null
        keyCode == home -> RemoteAlias.Home
        keyCode == menu || keyCode in MENU_DEFAULTS -> RemoteAlias.Menu
        else -> null
    }

    fun with(alias: RemoteAlias, keyCode: Int): RemoteKeyMap = when (alias) {
        RemoteAlias.Menu -> copy(menu = keyCode, home = if (home == keyCode) 0 else home)
        RemoteAlias.Home -> copy(home = keyCode, menu = if (menu == keyCode) 0 else menu)
    }

    fun key(alias: RemoteAlias): Int = if (alias == RemoteAlias.Menu) menu else home

    companion object {
        /** What a TV may send for its own options button. */
        val MENU_DEFAULTS = setOf(KeyEvent.KEYCODE_TV_CONTENTS_MENU, KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU)

        private val USED = setOf(KeyEvent.KEYCODE_UNKNOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)

        fun assignable(keyCode: Int): Boolean = keyCode !in USED
    }
}

enum class RemoteLearnResult { Saved, Used, Cancelled, Timeout }

/** One "press the button now" round started from Settings. */
class RemoteKeyLearning(private val current: () -> RemoteKeyMap, private val save: (RemoteKeyMap) -> Unit) {
    var target: RemoteAlias? = null
        private set

    fun start(alias: RemoteAlias) { target = alias }

    fun timeout(): RemoteLearnResult? = target?.let { target = null; RemoteLearnResult.Timeout }

    /** The first press after [start]. Back leaves without a change. */
    fun pressed(keyCode: Int): RemoteLearnResult? {
        val alias = target ?: return null
        target = null
        return when {
            keyCode == KeyEvent.KEYCODE_BACK -> RemoteLearnResult.Cancelled
            !RemoteKeyMap.assignable(keyCode) -> RemoteLearnResult.Used
            else -> { save(current().with(alias, keyCode)); RemoteLearnResult.Saved }
        }
    }
}
