package dev.firetvlite.iptv.ui.navigation

import androidx.annotation.StringRes
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.NavIconKind

/** Top-level screens, in the order they appear in the navigation rail. */
enum class Destination(
    val icon: NavIconKind,
    @StringRes val labelRes: Int,
) {
    Home(NavIconKind.Home, R.string.nav_home),
    Live(NavIconKind.Live, R.string.nav_live),
    Guide(NavIconKind.Guide, R.string.nav_guide),
    Search(NavIconKind.Search, R.string.nav_search),
    Favorites(NavIconKind.Favorites, R.string.nav_favorites),
    Films(NavIconKind.Guide, R.string.nav_films),
    Settings(NavIconKind.Settings, R.string.nav_settings),
}
