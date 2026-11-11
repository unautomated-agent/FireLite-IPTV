package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ListZapContext
import dev.firetvlite.iptv.data.library.ChannelTabPolicy
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.UpdateBanner
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import dev.firetvlite.iptv.update.UpdateState
import dev.firetvlite.iptv.update.pendingRelease

@Composable
fun HomeScreen(
    library: LibraryViewModel, player: PlayerViewModel, active: Boolean, returnTarget: BrowserTarget?,
    updateState: UpdateState, onInstallUpdate: () -> Unit, onLive: () -> Unit, onSettings: () -> Unit,
) {
    val recent by library.recentRows.collectAsState()
    val favorites by library.homeFavorites.collectAsState()
    val source by library.source.collectAsState()
    val sections = listOf(GroupKey.Recent to recent, GroupKey.Favorites to favorites)
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val emptyFocus = remember { FocusRequester() }
    LaunchedEffect(active, returnTarget?.token, recent.isNotEmpty(), favorites.isNotEmpty(), source?.id) {
        if (!active) return@LaunchedEffect
        val targetSection = ChannelTabPolicy.homeSection(
            sections.map { (group, rows) -> group to rows.map { it.channel.streamUrl } },
            returnTarget?.group, returnTarget?.channel?.streamUrl)
        if (targetSection < 0) emptyFocus.requestFocusWhenReady()
        else {
            listState.scrollToItem(targetSection)
            val (group, rows) = sections[targetSection]
            val index = rows.indexOfFirst { it.channel.streamUrl == returnTarget?.channel?.streamUrl }.coerceAtLeast(0)
            requesters.getOrPut("${group.id}:${rows[index].channel.id}") { FocusRequester() }.requestFocusWhenReady()
        }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        sections.forEach { (group, rows) ->
            item(key = group.id) {
                HomeChannels(rows, group, library, requesters, returnTarget,
                    onPlay = { index -> player.open(ListZapContext(rows.map { it.channel }), index, group) })
            }
        }
        if (recent.isEmpty() && favorites.isEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                FLText(stringResource(R.string.home_empty), style = FireLiteTheme.typography.body)
                PrimaryButton(stringResource(if (source == null) R.string.live_open_settings else R.string.nav_live),
                    onClick = if (source == null) onSettings else onLive, modifier = Modifier.focusRequester(emptyFocus))
            }
        }
        if (updateState.pendingRelease != null) item { UpdateBanner(updateState, onInstallUpdate) }
    }
}

@Composable
private fun HomeChannels(
    rows: List<ChannelRow>, group: GroupKey, library: LibraryViewModel,
    requesters: MutableMap<String, FocusRequester>, returnTarget: BrowserTarget?, onPlay: (Int) -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(returnTarget?.token, rows) {
        val index = rows.indexOfFirst { it.channel.streamUrl == returnTarget?.channel?.streamUrl }
        if (index >= 0) state.scrollToItem(index)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FLText(stringResource(if (group == GroupKey.Recent) R.string.home_recent else R.string.nav_favorites),
            style = FireLiteTheme.typography.headline)
        LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(rows, key = { _, row -> row.channel.id }) { index, row ->
                ChannelRowItem(row, null, System.currentTimeMillis(), false, {}, { onPlay(index) },
                    { library.toggleFavorite(row.channel.streamUrl) },
                    Modifier.width(340.dp).focusRequester(requesters.getOrPut("${group.id}:${row.channel.id}") { FocusRequester() }))
            }
        }
    }
}
