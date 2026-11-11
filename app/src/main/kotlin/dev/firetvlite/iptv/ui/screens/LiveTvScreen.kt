package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ImportState
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.NavIcon
import dev.firetvlite.iptv.ui.components.NavIconKind
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.pluralString
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.GroupItem
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

/**
 * Live TV browser: groups on the left, the channels of the selected group in the middle (loaded page by page),
 * details of the focused channel on the right. OK starts the channel; the Menu button toggles the favourite.
 *
 * There is deliberately no live preview: a preview would be a second stream next to the one the user opens.
 */
@Composable
fun LiveTvScreen(
    library: LibraryViewModel,
    player: PlayerViewModel,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source by library.source.collectAsState()
    val channelCount by library.channelCount.collectAsState()
    val importState by library.importState.collectAsState()

    when {
        source == null -> MessageWithAction(
            text = stringResource(R.string.live_no_source),
            actionText = stringResource(R.string.live_open_settings),
            onAction = onOpenSettings,
            modifier = modifier,
        )
        channelCount == 0 && importState == ImportState.Running -> MessageWithAction(
            text = stringResource(R.string.live_loading),
            modifier = modifier,
        )
        channelCount == 0 -> MessageWithAction(
            text = stringResource(R.string.live_empty_list),
            actionText = stringResource(R.string.live_open_settings),
            onAction = onOpenSettings,
            modifier = modifier,
        )
        else -> ChannelBrowser(library = library, player = player, modifier = modifier)
    }
}

@Composable
private fun ChannelBrowser(
    library: LibraryViewModel,
    player: PlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val groups by library.groups.collectAsState()
    val selected by library.selectedGroup.collectAsState()
    val pagingItems = key(selected) {
        remember(selected) { library.channels(selected) }.collectAsLazyPagingItems()
    }
    val scope = rememberCoroutineScope()
    val groupListState = rememberLazyListState()
    val channelListState = remember(selected) { LazyListState() }
    val groupRequesters = remember(groups.map { it.key }) { groups.associate { it.key to FocusRequester() } }
    val firstChannelFocus = remember(selected) { FocusRequester() }
    fun focusGroup(index: Int) {
        if (index !in groups.indices) return
        scope.launch {
            if (groupListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                groupListState.scrollToItem(index)
            }
            groupRequesters[groups[index].key]?.requestFocusWhenReady()
        }
    }
    fun focusChannels() {
        if (pagingItems.itemCount == 0) return
        scope.launch {
            channelListState.scrollToItem(0)
            firstChannelFocus.requestFocusWhenReady()
        }
    }
    fun focusSelectedGroup() {
        focusGroup(groups.indexOfFirst { it.key == selected })
    }

    var focusedChannel by remember(selected) { mutableStateOf<ChannelRow?>(null) }
    val initialGroup = remember { library.selectedGroup.value }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(groups.isNotEmpty()) {
        if (groups.isNotEmpty() && !initialFocusRequested) {
            initialFocusRequested = true
            val index = groups.indexOfFirst { it.key == initialGroup }
            if (index >= 0) {
                groupListState.scrollToItem(index)
                groupRequesters[initialGroup]?.requestFocusWhenReady()
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.width(188.dp).fillMaxHeight()) {
            FLText(
                text = stringResource(R.string.live_groups),
                style = FireLiteTheme.typography.headline,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            LazyColumn(
                state = groupListState,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) focusChannels()
                            true
                        }
                        else -> false
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = groups, key = { it.key.id }) { group ->
                    GroupRow(
                        group = group,
                        selected = group.key == selected,
                        onFocused = { library.selectGroup(group.key) },
                        onClick = { focusChannels() },
                        modifier = Modifier.focusRequester(groupRequesters.getValue(group.key)),
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            FLText(
                text = stringResource(R.string.live_channels),
                style = FireLiteTheme.typography.headline,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (pagingItems.itemCount == 0 && pagingItems.loadState.refresh is LoadState.NotLoading) {
                    FLText(
                        text = stringResource(R.string.live_empty_group),
                        style = FireLiteTheme.typography.body,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                LazyColumn(
                    state = channelListState,
                    modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                        if (event.key == Key.DirectionLeft) {
                            if (event.type == KeyEventType.KeyDown) focusSelectedGroup()
                            true
                        } else false
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.channel.id }) { index ->
                        val row = pagingItems[index]
                        if (row != null) {
                            ChannelRowItem(
                                row = row,
                                modifier = if (index == 0) Modifier.focusRequester(firstChannelFocus) else Modifier,
                                onFocused = { focusedChannel = row },
                                onClick = {
                                    scope.launch {
                                        library.zapContext(selected)?.let { player.open(it, index) }
                                    }
                                },
                                onMenu = { library.toggleFavorite(row.channel.streamUrl) },
                            )
                        }
                    }
                }
            }
        }

        DetailPanel(row = focusedChannel, modifier = Modifier.width(236.dp))
    }
}

@Composable
private fun GroupRow(
    group: GroupItem,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusSurface(
        onClick = onClick,
        onFocusChanged = { focused -> if (focused) onFocused() },
        modifier = modifier.fillMaxWidth(),
        background = SolidColor(
            if (selected) FireLiteTheme.colors.surfaceHigh else FireLiteTheme.colors.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            FLText(
                text = groupLabel(group.key),
                style = FireLiteTheme.typography.title,
                color = if (selected) FireLiteTheme.colors.accent else FireLiteTheme.colors.textPrimary,
                maxLines = 1,
            )
            FLText(
                text = pluralString(R.plurals.channel_count, group.channelCount),
                style = FireLiteTheme.typography.label,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelRowItem(
    row: ChannelRow,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val channel = row.channel
    FocusSurface(
        onClick = onClick,
        onFocusChanged = { focused -> if (focused) onFocused() },
        onMenuKey = onMenu,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChannelLogo(name = channel.name, logoUrl = channel.logoUrl)
            FLText(
                text = channel.name,
                style = FireLiteTheme.typography.title,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (channel.catchupDays > 0) {
                FLText(
                    text = stringResource(R.string.badge_catchup),
                    style = FireLiteTheme.typography.label,
                    color = FireLiteTheme.colors.accent,
                )
            }
            if (row.isFavorite) {
                NavIcon(
                    kind = NavIconKind.Favorites,
                    tint = FireLiteTheme.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun DetailPanel(
    row: ChannelRow?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(FireLiteTheme.shapes.large)
            .background(FireLiteTheme.colors.surface)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (row != null) {
            val channel = row.channel
            ChannelLogo(name = channel.name, logoUrl = channel.logoUrl, size = 72.dp)
            Spacer(modifier = Modifier.height(4.dp))
            FLText(text = channel.name, style = FireLiteTheme.typography.headline, maxLines = 3)
            FLText(
                text = stringResource(R.string.detail_group, groupLabel(GroupKey.Named(channel.groupName))),
                style = FireLiteTheme.typography.body,
                maxLines = 2,
            )
            if (channel.catchupDays > 0) {
                FLText(
                    text = pluralString(R.plurals.catchup_days, channel.catchupDays),
                    style = FireLiteTheme.typography.body,
                    color = FireLiteTheme.colors.accent,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        FLText(
            text = stringResource(R.string.live_hint),
            style = FireLiteTheme.typography.label,
        )
    }
}

@Composable
private fun MessageWithAction(
    text: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(actionText) {
        if (actionText != null) requester.requestFocusWhenReady()
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FLText(text = text, style = FireLiteTheme.typography.headline)
            if (actionText != null) {
                PrimaryButton(
                    text = actionText,
                    onClick = onAction,
                    modifier = Modifier.focusRequester(requester),
                )
            }
        }
    }
}

@Composable
internal fun groupLabel(key: GroupKey): String = when (key) {
    GroupKey.Favorites -> stringResource(R.string.group_favorites)
    GroupKey.Recent -> stringResource(R.string.group_recent)
    GroupKey.All -> stringResource(R.string.group_all)
    is GroupKey.Named -> key.name.ifEmpty { stringResource(R.string.group_ungrouped) }
}
