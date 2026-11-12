package dev.firetvlite.iptv.ui.navigation

import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.PlayerMode

enum class StreamPanel { Films, Settings }

fun visiblePanel(mode: PlayerMode, current: StreamPanel): StreamPanel? = when (mode) {
    PlayerMode.Closed -> StreamPanel.Settings
    PlayerMode.Panel -> current
    else -> null
}

fun canChooseChannel(hasSource: Boolean, channelCount: Int): Boolean = hasSource && channelCount > 0

fun screenBackReturnsToPlayback(mode: PlayerMode, hasChannel: Boolean): Boolean =
    hasChannel && (mode == PlayerMode.Panel || mode == PlayerMode.Guide)

enum class GuideBackAction { Pass, Consume, ReturnToPlayback, Exit }

fun guideBackAction(mode: PlayerMode, hasChannel: Boolean, keyUp: Boolean, canceled: Boolean): GuideBackAction = when {
    mode != PlayerMode.Guide -> GuideBackAction.Pass
    !keyUp || canceled -> GuideBackAction.Consume
    hasChannel -> GuideBackAction.ReturnToPlayback
    else -> GuideBackAction.Exit
}

enum class QuickMenuAction { Films, Settings, Favorite, Audio }

fun quickMenuItems(favorite: Boolean): List<Pair<QuickMenuAction, Int>> = listOf(
    QuickMenuAction.Films to R.string.nav_films,
    QuickMenuAction.Settings to R.string.nav_settings,
    QuickMenuAction.Favorite to if (favorite) R.string.quick_menu_remove_favorite else R.string.quick_menu_add_favorite,
    QuickMenuAction.Audio to R.string.quick_menu_audio,
)
