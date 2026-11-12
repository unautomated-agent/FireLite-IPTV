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

enum class QuickMenuAction { Audio, Favorite, Guide, Films, Settings }

fun quickMenuItems(favorite: Boolean): List<Pair<QuickMenuAction, Int>> = listOf(
    QuickMenuAction.Audio to R.string.quick_menu_audio,
    QuickMenuAction.Favorite to if (favorite) R.string.quick_menu_remove_favorite else R.string.quick_menu_add_favorite,
    QuickMenuAction.Guide to R.string.nav_guide,
    QuickMenuAction.Films to R.string.nav_films,
    QuickMenuAction.Settings to R.string.nav_settings,
)
