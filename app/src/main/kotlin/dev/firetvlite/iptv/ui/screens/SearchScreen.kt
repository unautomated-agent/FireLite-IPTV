package dev.firetvlite.iptv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.paging.PagingData
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ListZapContext
import dev.firetvlite.iptv.ui.components.LabeledTextField
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.pluralString
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.map

@Composable
fun SearchScreen(library: LibraryViewModel, player: PlayerViewModel, active: Boolean, returnTarget: BrowserTarget?) {
    val query by library.searchText.collectAsState()
    val results by library.searchRows.collectAsState()
    val returnedQuery = remember(returnTarget?.token) { query }
    ChannelListScreen(stringResource(R.string.nav_search),
        stringResource(if (query.isBlank()) R.string.search_prompt else R.string.search_empty),
        stringResource(R.string.search_hint), remember { library.searchRows.map { PagingData.from(it) } },
        library, active, returnTarget?.takeIf { query == returnedQuery }, {},
        findIndex = { url -> results.indexOfFirst { it.channel.streamUrl == url }.takeIf { it >= 0 } },
        onPlay = { _, index -> player.open(ListZapContext(results.map { it.channel }), index, GroupKey.All) },
        header = { requester ->
            LabeledTextField(label = stringResource(R.string.search_label), value = query,
                onValueChange = library::setSearchText, modifier = Modifier.focusRequester(requester))
            FLText(pluralString(R.plurals.channel_count, results.size), style = FireLiteTheme.typography.label)
        })
}
