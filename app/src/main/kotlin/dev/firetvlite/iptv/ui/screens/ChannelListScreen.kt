package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.playback.RemotePress
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.components.wallClock
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
internal fun ChannelListScreen(
    title: String, emptyText: String, hint: String, channels: Flow<PagingData<ChannelRow>>,
    library: LibraryViewModel, active: Boolean, returnTarget: BrowserTarget?, onEmpty: () -> Unit,
    findIndex: suspend (String) -> Int?, onPlay: (ChannelRow, Int) -> Unit,
    onLongClick: ((ChannelRow) -> Unit)? = null,
    header: (@Composable (FocusRequester) -> Unit)? = null,
) {
    val source by library.source.collectAsState()
    val rows = channels.collectAsLazyPagingItems()
    val state = rememberLazyListState()
    val requesters = remember(source?.id) { mutableMapOf<Long, FocusRequester>() }
    val fallback = remember { FocusRequester() }
    val now by wallClock()
    val visibleIds = state.layoutInfo.visibleItemsInfo.mapNotNull { info ->
        if (info.index < rows.itemCount) rows.peek(info.index)?.channel?.id else null
    }
    val programmes by remember(visibleIds, now) { library.currentProgrammes(visibleIds, now) }.collectAsState(emptyList())
    val byChannel = programmes.associateBy { it.channelId }
    LaunchedEffect(active, returnTarget?.token, rows.itemCount, source?.id) {
        if (!active) return@LaunchedEffect
        if (rows.itemCount == 0 || (header != null && returnTarget == null)) fallback.requestFocusWhenReady()
        else {
            val index = returnTarget?.channel?.streamUrl?.let { findIndex(it) }?.coerceIn(0, rows.itemCount - 1) ?: 0
            state.scrollToItem(index)
            val row = snapshotFlow { rows.peek(index) }.filterNotNull().first()
            requesters.getOrPut(row.channel.id) { FocusRequester() }.requestFocusWhenReady()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FLText(title, style = FireLiteTheme.typography.headline)
        header?.invoke(fallback)
        if (rows.itemCount == 0) {
            FLText(emptyText, style = FireLiteTheme.typography.body)
            if (header == null) PrimaryButton(stringResource(R.string.nav_live), onEmpty, Modifier.focusRequester(fallback))
        }
        LazyColumn(state = state, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows.itemCount, key = rows.itemKey { it.channel.id }) { index ->
                rows[index]?.let { row ->
                    CollectionRow(row, byChannel[row.channel.id]?.programme, now,
                        onClick = { onPlay(row, index) }, onMenu = { library.toggleFavorite(row.channel.streamUrl) },
                        onLongClick = onLongClick?.let { { it(row) } },
                        modifier = Modifier.focusRequester(requesters.getOrPut(row.channel.id) { FocusRequester() }))
                }
            }
        }
        FLText(hint, style = FireLiteTheme.typography.label)
    }
}

@Composable
private fun CollectionRow(
    row: ChannelRow, programme: ProgrammeEntity?, now: Long,
    onClick: () -> Unit, onMenu: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier,
) {
    val press = remember { RemotePress() }
    val scope = rememberCoroutineScope()
    val timer = remember { arrayOfNulls<Job>(1) }
    DisposableEffect(Unit) { onDispose { timer[0]?.cancel() } }
    ChannelRowItem(row, programme, now, false, {}, onClick, onMenu,
        modifier.onPreviewKeyEvent { event ->
            if (onLongClick == null || event.key !in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) false
            else {
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    press.begin()
                    timer[0]?.cancel()
                    timer[0] = scope.launch { delay(600); if (press.longPress()) onLongClick() }
                } else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress) {
                    timer[0]?.cancel()
                    if (press.longPress()) onLongClick()
                } else if (event.type == KeyEventType.KeyUp) {
                    timer[0]?.cancel()
                    if (press.release()) onClick()
                }
                true
            }
        })
}
