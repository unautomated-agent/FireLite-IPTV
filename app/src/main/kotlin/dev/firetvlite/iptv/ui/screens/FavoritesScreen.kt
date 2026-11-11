package dev.firetvlite.iptv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@Composable
fun FavoritesScreen(library: LibraryViewModel, player: PlayerViewModel, active: Boolean,
    returnTarget: BrowserTarget?, onLive: () -> Unit) {
    val scope = rememberCoroutineScope()
    ChannelListScreen(stringResource(R.string.nav_favorites), stringResource(R.string.favorites_empty),
        stringResource(R.string.favorites_hint), remember { library.channels(GroupKey.Favorites) },
        library, active, returnTarget, onLive,
        findIndex = { library.channelIndex(GroupKey.Favorites, it) },
        onPlay = { _, index -> scope.launch {
            library.zapContext(GroupKey.Favorites)?.let { player.open(it, index, GroupKey.Favorites) }
        } }, onLongClick = { library.removeFavorite(it.channel.streamUrl) })
}
