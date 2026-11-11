package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
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
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.epg.CurrentAndNext
import dev.firetvlite.iptv.playback.PlaybackTime
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.programmeTimes
import dev.firetvlite.iptv.ui.components.wallClock
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Live TV browser: groups on the left, the channels of the selected group in the middle (loaded page by page),
 * details of the focused channel on the right. OK starts the channel; the Menu button toggles the favourite.
 *
 * Preview and fullscreen share the single player hosted by the shell.
 */
@Composable
fun LiveTvScreen(
    library: LibraryViewModel,
    player: PlayerViewModel,
    active: Boolean,
    onPreviewBounds: (Rect) -> Unit,
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
        else -> {
            val mode by player.mode.collectAsState()
            ChannelBrowser(
                library = library, player = player,
                active = active && mode.acceptsPreview,
                onPreviewBounds = onPreviewBounds, modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PlayerChannelOverlay(library: LibraryViewModel, player: PlayerViewModel) {
    var showGroups by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        ChannelBrowser(
            library = library, player = player, active = true,
            overlay = true, showGroups = showGroups, onShowGroups = { showGroups = it },
            modifier = Modifier.fillMaxWidth(if (showGroups) 0.66f else 0.42f).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Color(0xBF101820), Color(0xD2101820))))
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        )
    }
}

@Composable
private fun ChannelBrowser(
    library: LibraryViewModel,
    player: PlayerViewModel,
    active: Boolean,
    overlay: Boolean = false,
    showGroups: Boolean = true,
    onShowGroups: (Boolean) -> Unit = {},
    onPreviewBounds: (Rect) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val groups by library.groups.collectAsState()
    val selected by library.selectedGroup.collectAsState()
    val restore by player.browserTarget.collectAsState()
    val pagingItems = key(selected) {
        remember(selected) { library.channels(selected) }.collectAsLazyPagingItems()
    }
    val scope = rememberCoroutineScope()
    val groupListState = rememberLazyListState()
    val channelListState = remember(selected) { LazyListState() }
    val now by wallClock()
    val limits by remember(selected) { library.guideLimits(selected) }.collectAsState(initial = 0 to null)
    val bounds = GuideTimeline.bounds(now, limits.first, limits.second)
    var requestedStart by remember(selected) { mutableStateOf(now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)) }
    val windowStart = GuideTimeline.windowStart(requestedStart, bounds)
    var focusedTime by remember(selected) { mutableStateOf(now) }
    var programmeFocus by remember(selected) { mutableStateOf(false) }
    var focusedProgramme by remember(selected) { mutableStateOf<ProgrammeEntity?>(null) }
    fun shiftWindow(delta: Long) {
        val next = GuideTimeline.windowStart(windowStart + delta, bounds)
        focusedTime = (focusedTime + next - windowStart).coerceIn(next, next + GuideTimeline.WINDOW - 1)
        requestedStart = next
        programmeFocus = true
    }
    var visibleIds by remember(selected) { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(selected, pagingItems) {
        snapshotFlow {
            channelListState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                if (info.index < pagingItems.itemCount) pagingItems.peek(info.index)?.channel?.id else null
            }
        }.distinctUntilChanged().collect { visibleIds = it }
    }
    val queryFrom = if (overlay) now else windowStart
    val queryUntil = if (overlay) now + 1 else windowStart + GuideTimeline.WINDOW
    val programmes by remember(visibleIds, queryFrom, queryUntil) { library.programmes(visibleIds, queryFrom, queryUntil) }
        .collectAsState(initial = emptyList())
    val programmesByChannel = remember(programmes) { programmes.groupBy { it.channelId }.mapValues { (_, entries) -> entries.map { it.programme } } }
    val guideChannels by remember(visibleIds) { library.guideChannels(visibleIds) }.collectAsState(initial = emptySet())
    val groupRequesters = remember(groups.map { it.key }) { groups.associate { it.key to FocusRequester() } }
    val firstChannelFocus = remember(selected) { FocusRequester() }
    val restoreFocus = remember(selected) { FocusRequester() }
    var restoreIndex by remember(selected) { mutableStateOf<Int?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<GroupKey, Int>?>(null) }
    var handledRestore by remember { mutableStateOf<Int?>(null) }
    var channelsHaveFocus by remember { mutableStateOf(false) }
    val rowRequesters = remember(selected) { HashMap<Int, FocusRequester>() }
    fun rowRequester(index: Int): FocusRequester = when (index) {
        restoreIndex -> restoreFocus
        0 -> firstChannelFocus
        else -> rowRequesters.getOrPut(index) { FocusRequester() }
    }
    fun focusRow(index: Int) {
        if (index !in 0 until pagingItems.itemCount) return
        scope.launch {
            if (channelListState.layoutInfo.visibleItemsInfo.none { it.index == index }) channelListState.scrollToItem(index)
            snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
            rowRequester(index).requestFocusWhenReady()
        }
    }
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
            val index = restoreIndex ?: 0
            channelListState.scrollToItem(index)
            if (index == restoreIndex) restoreFocus.requestFocusWhenReady() else firstChannelFocus.requestFocusWhenReady()
        }
    }
    fun focusSelectedGroup() {
        focusGroup(groups.indexOfFirst { it.key == selected })
    }

    var focusedChannel by remember(selected) { mutableStateOf<ChannelRow?>(null) }
    val detailGuide by remember(focusedChannel?.channel?.id, now) {
        focusedChannel?.channel?.id?.let { library.guide(it, now) } ?: flowOf(CurrentAndNext(null, null))
    }.collectAsState(initial = CurrentAndNext(null, null))
    var focusedIndex by remember(selected) { mutableStateOf(0) }
    LaunchedEffect(active, focusedChannel?.channel?.streamUrl, focusedIndex, selected, channelsHaveFocus) {
        if (overlay) return@LaunchedEffect
        player.cancelPreview()
        if (active && channelsHaveFocus && focusedChannel != null) {
            library.zapContext(selected)?.let { player.preview(it, focusedIndex, selected) }
        }
    }
    DisposableEffect(Unit) { onDispose { if (!overlay) player.cancelPreview() } }
    LaunchedEffect(active, restore?.token) {
        val target = restore ?: return@LaunchedEffect
        if (!active || handledRestore == target.token) return@LaunchedEffect
        focusedChannel = null
        programmeFocus = false
        focusedTime = now
        requestedStart = now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)
        var group = target.group
        var index = library.channelIndex(group, target.channel.streamUrl)
        if (index == null) {
            group = GroupKey.Named(target.channel.groupName)
            index = library.channelIndex(group, target.channel.streamUrl)
        }
        if (index != null) {
            pendingRestore = group to index
            library.selectGroup(group)
        }
    }
    LaunchedEffect(active, pendingRestore, selected, pagingItems.itemCount) {
        val (group, index) = pendingRestore ?: return@LaunchedEffect
        if (!active || selected != group || index !in 0 until pagingItems.itemCount) return@LaunchedEffect
        restoreIndex = index
        channelListState.scrollToItem(index)
        snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
        restoreFocus.requestFocusWhenReady()
        handledRestore = restore?.token
        pendingRestore = null
    }
    val initialGroup = remember { library.selectedGroup.value }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(groups.isNotEmpty(), active) {
        if (active && !overlay && restore == null && groups.isNotEmpty() && !initialFocusRequested) {
            initialFocusRequested = true
            val index = groups.indexOfFirst { it.key == initialGroup }
            if (index >= 0) {
                groupListState.scrollToItem(index)
                groupRequesters[initialGroup]?.requestFocusWhenReady()
            }
        }
    }
    LaunchedEffect(showGroups) {
        if (overlay && showGroups) focusSelectedGroup()
    }
    LaunchedEffect(showGroups, pagingItems.itemCount, pendingRestore) {
        if (overlay && !showGroups && !channelsHaveFocus && handledRestore != null && pendingRestore == null) {
            focusChannels()
        }
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .onPreviewKeyEvent { event ->
                if (overlay || event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.MediaRewind -> { shiftWindow(-GuideTimeline.HOUR); true }
                    Key.MediaFastForward -> { shiftWindow(GuideTimeline.HOUR); true }
                    Key.MediaPlayPause -> {
                        focusedTime = now
                        requestedStart = now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)
                        programmeFocus = true
                        true
                    }
                    else -> false
                }
            }
            .padding(start = 16.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showGroups) Column(modifier = Modifier.width(if (overlay) 188.dp else 140.dp).fillMaxHeight()) {
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
                            if (event.type == KeyEventType.KeyDown) {
                                if (overlay) onShowGroups(false)
                                focusChannels()
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (!overlay) return@onPreviewKeyEvent false
                            if (event.type == KeyEventType.KeyDown) player.closeOverlay()
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
                        translucent = overlay,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (!overlay) GuideHeader(windowStart) else FLText(
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
                    modifier = Modifier.fillMaxSize().onFocusChanged { channelsHaveFocus = it.hasFocus }.onPreviewKeyEvent { event ->
                        if (overlay && event.key == Key.DirectionLeft) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (overlay && !showGroups) onShowGroups(true) else focusSelectedGroup()
                            }
                            true
                        } else false
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count = pagingItems.itemCount, key = pagingItems.itemKey { it.channel.id }) { index ->
                        val row = pagingItems[index]
                        if (row != null) {
                            if (!overlay) ProgrammeGridRow(
                                row = row,
                                programmes = programmesByChannel[row.channel.id].orEmpty(),
                                hasGuide = row.channel.id in guideChannels,
                                start = windowStart, now = now, focusedTime = focusedTime, programmeFocus = programmeFocus,
                                onProgrammeFocus = { programmeFocus = it }, onTime = { focusedTime = it },
                                onHorizontalEdge = { forward, target ->
                                    val next = GuideTimeline.reveal(windowStart, target, bounds)
                                    if (next == windowStart) { if (!forward) programmeFocus = false }
                                    else {
                                        focusedTime = target.coerceIn(next, next + GuideTimeline.WINDOW - 1)
                                        requestedStart = next
                                    }
                                },
                                onVertical = { focusRow(index + it) },
                                onGroups = { focusSelectedGroup() },
                                onFocused = { focusedChannel = row; focusedIndex = index },
                                onDetails = { focusedProgramme = it },
                                onSelect = { cell ->
                                    scope.launch {
                                        val programme = cell?.programme
                                        val action = GuideTimeline.action(programme, now, row.channel.catchupDays, CatchupUrlBuilder.isSupported(row.channel),
                                            cell?.let { focusedTime.coerceIn(it.start, it.stop - 1) } ?: now, cell != null && row.channel.id in guideChannels)
                                        if (action != GuideAction.Details) library.zapContext(selected)?.let {
                                            if (action == GuideAction.Archive) player.openArchive(it, index, selected, programme!!.start)
                                            else player.open(it, index, selected)
                                        }
                                    }
                                },
                                onMenu = { library.toggleFavorite(row.channel.streamUrl) },
                                modifier = Modifier.focusRequester(rowRequester(index)),
                            ) else ChannelRowItem(
                                row = row,
                                programme = programmesByChannel[row.channel.id]?.maxByOrNull { it.start },
                                now = now,
                                translucent = overlay,
                                modifier = when (index) {
                                    restoreIndex -> Modifier.focusRequester(restoreFocus)
                                    0 -> Modifier.focusRequester(firstChannelFocus)
                                    else -> Modifier
                                },
                                onFocused = { focusedChannel = row; focusedIndex = index },
                                onClick = {
                                    scope.launch {
                                        library.zapContext(selected)?.let { player.open(it, index, selected) }
                                    }
                                },
                                onMenu = { library.toggleFavorite(row.channel.streamUrl) },
                            )
                        } else {
                            Spacer(Modifier.fillMaxWidth().height(80.dp))
                        }
                    }
                }
                if (!overlay && now >= windowStart && now < windowStart + GuideTimeline.WINDOW) Canvas(Modifier.matchParentSize()) {
                    val name = GuideNameWidth.toPx()
                    val inset = 6.dp.toPx()
                    val x = inset + name + (size.width - name - 2 * inset) * GuideTimeline.fraction(now, windowStart)
                    drawLine(Color(0xFFFF6B6B), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }
            }
        }

        if (!overlay) DetailPanel(row = focusedChannel, guide = detailGuide,
            focusedProgramme = focusedProgramme, programmeFocus = programmeFocus, now = now,
            onPreviewBounds = onPreviewBounds, modifier = Modifier.width(188.dp))
    }
}

@Composable
private fun GroupRow(
    group: GroupItem,
    selected: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    translucent: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        onFocusChanged = { focused -> if (focused) onFocused() },
        modifier = modifier.fillMaxWidth(),
        background = SolidColor(
            if (translucent) {
                if (selected) FireLiteTheme.colors.accent.copy(alpha = 0.12f) else Color.Transparent
            } else if (selected) FireLiteTheme.colors.surfaceHigh else FireLiteTheme.colors.surface,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            FLText(
                text = groupLabel(group.key),
                style = FireLiteTheme.typography.title,
                color = if (selected) FireLiteTheme.colors.accent else FireLiteTheme.colors.textPrimary,
                maxLines = 2,
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
    programme: ProgrammeEntity?,
    now: Long,
    translucent: Boolean,
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
        background = SolidColor(if (translucent) Color.Transparent else FireLiteTheme.colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ChannelLogo(name = channel.name, logoUrl = channel.logoUrl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FLText(text = channel.name, style = FireLiteTheme.typography.title, maxLines = 1)
                if (programme != null) {
                    FLText(text = programme.title, style = FireLiteTheme.typography.label,
                        color = FireLiteTheme.colors.textSecondary, maxLines = 1)
                    ProgressBar(PlaybackTime.progress(now, programme.start, programme.stop))
                }
            }
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
    guide: CurrentAndNext,
    focusedProgramme: ProgrammeEntity?,
    programmeFocus: Boolean,
    now: Long,
    onPreviewBounds: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(FireLiteTheme.shapes.large)
            .background(FireLiteTheme.colors.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            .onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) })
        if (row != null) {
            val channel = row.channel
            FLText(text = channel.name, style = FireLiteTheme.typography.headline, maxLines = 2)
            val programme = if (programmeFocus) focusedProgramme else guide.current
            programme?.let { current ->
                FLText(programmeTimes(current), style = FireLiteTheme.typography.label, color = FireLiteTheme.colors.accent)
                FLText(current.title, style = FireLiteTheme.typography.title, maxLines = 2)
                current.description?.let { FLText(it, style = FireLiteTheme.typography.label, maxLines = 3) }
            }
            val available = programme != null && programme.start <= now &&
                programme.start >= now - channel.catchupDays * GuideTimeline.DAY && CatchupUrlBuilder.isSupported(channel)
            FLText(stringResource(if (available) R.string.guide_archive_available else R.string.guide_unavailable),
                style = FireLiteTheme.typography.label, color = if (available) FireLiteTheme.colors.accent else FireLiteTheme.colors.textSecondary)
            if (!programmeFocus) guide.next?.let { next ->
                FLText(stringResource(R.string.epg_next, programmeTimes(next), next.title),
                    style = FireLiteTheme.typography.label, maxLines = 3)
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        FLText(
            text = stringResource(R.string.guide_hint),
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
