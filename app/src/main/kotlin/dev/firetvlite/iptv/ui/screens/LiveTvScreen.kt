package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.ui.components.ImdbRating

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.ui.components.ArchiveIcon
import dev.firetvlite.iptv.ui.components.DeadChannelIcon
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.epg.CurrentAndNext
import dev.firetvlite.iptv.playback.PlaybackTime
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.programmeTimes
import dev.firetvlite.iptv.ui.components.wallClock
import dev.firetvlite.iptv.ui.components.clockTime
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
import dev.firetvlite.iptv.ui.viewmodel.BrowserTarget
import dev.firetvlite.iptv.data.library.ChannelTabPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.firetvlite.iptv.playback.CategorySwitch
import dev.firetvlite.iptv.playback.PlayerMode
import dev.firetvlite.iptv.ui.components.remotePress

internal const val CHANNEL_OVERLAY_ROW_DP = 48
internal const val CHANNEL_OVERLAY_GAP_DP = 4
internal const val CHANNEL_OVERLAY_LOGO_DP = 32

internal fun channelGuideQueryMinute(time: Long): Long = Math.floorDiv(time, 60_000L)

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
    guideOnly: Boolean = false,
    returnTarget: BrowserTarget? = null,
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
                active = active && (mode.acceptsPreview || mode == PlayerMode.Guide || mode == PlayerMode.Panel),
                onPreviewBounds = onPreviewBounds, modifier = modifier,
                guideOnly = guideOnly, returnTarget = returnTarget,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun PlayerChannelOverlay(library: LibraryViewModel, player: PlayerViewModel, onQuickMenu: () -> Unit) {
    var showGroups by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        ChannelBrowser(
            library = library, player = player, active = true,
            overlay = true, showGroups = showGroups, onShowGroups = { showGroups = it },
            onQuickMenu = onQuickMenu,
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
    guideOnly: Boolean = false,
    returnTarget: BrowserTarget? = null,
    onQuickMenu: () -> Unit = {},
) {
    val groups by library.groups.collectAsState()
    val selected by library.selectedGroup.collectAsState()
    val playerRestore by player.browserTarget.collectAsState()
    val restore = if (guideOnly) returnTarget else playerRestore
    val scope = rememberCoroutineScope()
    val channelFlows = remember { mutableMapOf<GroupKey, Flow<PagingData<ChannelRow>>>() }
    val pagingItems = key(selected) {
        channelFlows.getOrPut(selected) { library.channels(selected).cachedIn(scope) }.collectAsLazyPagingItems()
    }
    val groupListState = rememberLazyListState()
    val channelListState = rememberLazyListState()
    val browserFocus = remember { FocusRequester() }
    val categoryFocus = remember { CategoryFocusMemory() }
    val focusGeneration = categoryFocus.generation
    var pendingCategoryFocus by remember { mutableStateOf<CategoryFocusRequest?>(null) }
    var focusJob by remember { mutableStateOf<Job?>(null) }
    val guideFocus = remember { GuideFocusMemory() }
    var manualGuideFocus by remember { mutableStateOf(false) }
    val now by wallClock()
    val limits by remember(selected) { library.guideLimits(selected) }.collectAsState(initial = 0 to null)
    val bounds = GuideTimeline.bounds(now, limits.first, limits.second)
    var requestedStart by remember(selected) { mutableStateOf(if (guideOnly) guideFocus.cell(selected, now).windowStart
        else now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)) }
    val windowStart = GuideTimeline.windowStart(requestedStart, bounds)
    var focusedTime by remember(selected) { mutableStateOf(if (guideOnly) guideFocus.cell(selected, now).time else now) }
    var programmeFocus by remember(selected) { mutableStateOf(guideOnly && guideFocus.cell(selected, now).programme) }
    var focusedProgramme by remember(selected) { mutableStateOf<ProgrammeEntity?>(null) }
    fun switchCategory(delta: Int) {
        val current = categoryFocus.selection(library.selectedGroup.value)
        CategorySwitch.next(groups.map { it.key }, current, delta)?.takeIf { it != current }?.let {
            focusJob?.cancel()
            pendingCategoryFocus = categoryFocus.begin(it)
            browserFocus.requestFocus()
            library.selectGroup(it)
        }
    }
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
    val groupRequesters = remember { mutableMapOf<GroupKey, FocusRequester>() }
    groups.forEach { groupRequesters.getOrPut(it.key) { FocusRequester() } }
    var restoreIndex by remember(selected) { mutableStateOf<Int?>(null) }
    var pendingRestore by remember { mutableStateOf<Pair<GroupKey, Int>?>(null) }
    var handledRestore by remember { mutableStateOf<Int?>(null) }
    var channelsHaveFocus by remember { mutableStateOf(false) }
    fun saveGuideCell() {
        if (guideOnly && channelsHaveFocus && selected == library.selectedGroup.value) {
            guideFocus.save(selected, GuideCellFocus(requestedStart, focusedTime, programmeFocus))
        }
    }
    fun takeGuideFocus() {
        saveGuideCell()
        manualGuideFocus = true
        focusJob?.cancel()
        pendingRestore = null
        handledRestore = restore?.token
        categoryFocus.cancel()
        pendingCategoryFocus = null
    }
    val rowRequesters = remember { HashMap<Long, FocusRequester>() }
    fun rowRequester(row: ChannelRow): FocusRequester =
        rowRequesters.getOrPut(row.channel.id) { FocusRequester() }
    fun focusRow(index: Int) {
        if (pendingCategoryFocus != null) return
        if (index !in 0 until pagingItems.itemCount) return
        focusJob?.cancel()
        focusJob = scope.launch {
            if (channelListState.layoutInfo.visibleItemsInfo.none { it.index == index }) channelListState.animateScrollToItem(index)
            val row = snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
            rowRequester(row).requestFocusWhenReady()
        }
    }
    fun focusGroup(index: Int) {
        if (pendingCategoryFocus != null) return
        if (index !in groups.indices) return
        focusJob?.cancel()
        focusJob = scope.launch {
            if (groupListState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                groupListState.scrollToItem(index)
            }
            groupRequesters[groups[index].key]?.requestFocusWhenReady()
        }
    }
    fun focusChannels() {
        if (pendingCategoryFocus != null) return
        if (pagingItems.itemCount == 0) return
        focusJob?.cancel()
        focusJob = scope.launch {
            val target = categoryFocus.target(selected)
            val index = target?.index(library.channelIndex(selected, target.url), pagingItems.itemCount)
                ?: (restoreIndex ?: 0).coerceIn(0, pagingItems.itemCount - 1)
            if (channelListState.layoutInfo.visibleItemsInfo.none { it.index == index }) channelListState.animateScrollToItem(index)
            val row = snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
            rowRequester(row).requestFocusWhenReady()
        }
    }
    fun focusSelectedGroup() {
        if (guideOnly) takeGuideFocus()
        focusGroup(groups.indexOfFirst { it.key == library.selectedGroup.value })
    }
    fun moveGuideFocus(forward: Boolean, repeatCount: Int) {
        when (GuideFocusMemory.destination(forward, repeatCount)) {
            GuideFocusPane.Groups -> focusSelectedGroup()
            GuideFocusPane.Grid -> {
                takeGuideFocus()
                pendingCategoryFocus = categoryFocus.begin(library.selectedGroup.value)
                browserFocus.requestFocus()
            }
            null -> Unit
        }
    }

    var focusedChannel by remember { mutableStateOf<ChannelRow?>(null) }
    val detailGuide by remember(focusedChannel?.channel?.id, now) {
        focusedChannel?.channel?.id?.let { library.guide(it, now) } ?: flowOf(CurrentAndNext(null, null))
    }.collectAsState(initial = CurrentAndNext(null, null))
    var focusedIndex by remember { mutableStateOf(0) }
    fun openChannelHistory() {
        val channel = focusedChannel?.channel ?: return
        scope.launch {
            library.zapContext(selected)?.let { player.openTimeline(it, focusedIndex, selected, channel) }
        }
    }
    fun recordFocus(row: ChannelRow, index: Int) {
        if (library.selectedGroup.value != selected || !categoryFocus.record(selected, focusGeneration,
                ChannelFocusTarget(row.channel.streamUrl, index))) return
        focusedChannel = row
        focusedIndex = index
    }
    LaunchedEffect(selected, pagingItems.itemSnapshotList) {
        val url = categoryFocus.target(selected)?.url ?: return@LaunchedEffect
        val snapshot = pagingItems.itemSnapshotList
        val index = snapshot.items.indexOfFirst { it.channel.streamUrl == url }
        if (index >= 0) {
            val position = snapshot.placeholdersBefore + index
            if (categoryFocus.record(selected, focusGeneration, ChannelFocusTarget(url, position))) {
                focusedIndex = position
            }
        }
    }
    LaunchedEffect(active, focusedChannel?.channel?.streamUrl, focusedIndex, selected, channelsHaveFocus) {
        if (overlay || guideOnly) return@LaunchedEffect
        player.cancelPreview()
        if (active && channelsHaveFocus && focusedChannel != null) {
            library.zapContext(selected)?.let { player.preview(it, focusedIndex, selected) }
        }
    }
    DisposableEffect(selected) { onDispose { focusJob?.cancel() } }
    DisposableEffect(Unit) { onDispose { if (!overlay) player.cancelPreview() } }
    LaunchedEffect(active, restore?.token, manualGuideFocus) {
        val target = restore ?: return@LaunchedEffect
        if (guideOnly && manualGuideFocus) return@LaunchedEffect
        if (!active || handledRestore == target.token) return@LaunchedEffect
        programmeFocus = guideOnly
        focusedTime = now
        requestedStart = now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)
        var group = target.group
        var index = library.channelIndex(group, target.channel.streamUrl)
        if (index == null) {
            group = GroupKey.Named(target.channel.groupName)
            index = library.channelIndex(group, target.channel.streamUrl)
        }
        if (guideOnly && manualGuideFocus) return@LaunchedEffect
        if (index != null) {
            pendingRestore = group to index
            library.selectGroup(group)
        } else {
            pendingRestore = null
            handledRestore = target.token
        }
    }
    LaunchedEffect(active, pendingRestore, selected, pagingItems.itemCount, manualGuideFocus) {
        if (guideOnly && manualGuideFocus) return@LaunchedEffect
        val (group, index) = pendingRestore ?: return@LaunchedEffect
        if (!active || selected != group || index !in 0 until pagingItems.itemCount) return@LaunchedEffect
        focusJob?.cancel()
        focusJob = launch {
            restoreIndex = index
            channelListState.scrollToItem(index)
            val row = snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
            rowRequester(row).requestFocusWhenReady()
            handledRestore = restore?.token
            pendingRestore = null
        }
    }
    val initialGroup = remember { library.selectedGroup.value }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(groups.isNotEmpty(), active) {
        if (active && !overlay && restore == null && groups.isNotEmpty() && !initialFocusRequested && !manualGuideFocus) {
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
    LaunchedEffect(showGroups) {
        if (overlay && !showGroups && handledRestore != null && pendingRestore == null) {
            focusChannels()
        }
    }
    LaunchedEffect(pendingCategoryFocus, selected, pagingItems.loadState.refresh) {
        val request = pendingCategoryFocus ?: return@LaunchedEffect
        if (request.group != selected || pagingItems.loadState.refresh !is LoadState.NotLoading) return@LaunchedEffect
        focusJob?.cancel()
        focusJob = launch {
            fun current() = categoryFocus.isCurrent(request) && library.selectedGroup.value == request.group
            if (!current()) return@launch
            if (pagingItems.itemCount > 0) {
                val target = request.target
                val index = target?.index(library.channelIndex(selected, target.url), pagingItems.itemCount) ?: 0
                if (!current()) return@launch
                if (channelListState.layoutInfo.visibleItemsInfo.none { it.index == index }) channelListState.animateScrollToItem(index)
                val row = snapshotFlow { pagingItems.peek(index) }.filterNotNull().first()
                if (!current()) return@launch
                rowRequester(row).requestFocusWhenReady()
                if (!current()) return@launch
                if (categoryFocus.complete(request, ChannelFocusTarget(row.channel.streamUrl, index))) {
                    pendingCategoryFocus = null
                    focusedChannel = row
                    focusedIndex = index
                }
            } else if (categoryFocus.complete(request)) {
                pendingCategoryFocus = null
                if (guideOnly) focusSelectedGroup()
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxHeight()
            .focusRequester(browserFocus)
            .then(if (overlay) Modifier.remotePress(setOf(Key.Menu),
                onShort = { if (channelsHaveFocus) openChannelHistory() }, onLong = onQuickMenu) else Modifier)
            .then(if (overlay) Modifier
                .remotePress(setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter), onShort = {
                    val channel = focusedChannel?.channel
                    if (pendingCategoryFocus != null) Unit
                    else if (!channelsHaveFocus) onShowGroups(!showGroups)
                    else if (channel != null) scope.launch {
                        library.zapContext(selected)?.let { player.open(it, focusedIndex, selected) }
                    }
                }, onLong = { player.openGuide(selected, focusedChannel?.channel) }) else Modifier)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else if (overlay) when (event.key) {
                    Key.MediaRewind -> { if (event.nativeKeyEvent.repeatCount == 0) switchCategory(-1); true }
                    Key.MediaFastForward -> { if (event.nativeKeyEvent.repeatCount == 0) switchCategory(1); true }
                    else -> false
                } else when (event.key) {
                    Key.MediaRewind -> {
                        if (guideOnly) moveGuideFocus(false, event.nativeKeyEvent.repeatCount) else shiftWindow(-GuideTimeline.HOUR)
                        true
                    }
                    Key.MediaFastForward -> {
                        if (guideOnly) moveGuideFocus(true, event.nativeKeyEvent.repeatCount) else shiftWindow(GuideTimeline.HOUR)
                        true
                    }
                    Key.MediaPlayPause -> {
                        focusedTime = now
                        requestedStart = now / (GuideTimeline.HOUR / 2) * (GuideTimeline.HOUR / 2)
                        programmeFocus = true
                        true
                    }
                    else -> false
                }
            }
            .then(if (overlay || guideOnly) Modifier.focusable() else Modifier)
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
                        onFocused = {
                            if (pendingCategoryFocus == null && ChannelTabPolicy.canSelectGroup(restore?.token, handledRestore)) library.selectGroup(group.key)
                        },
                        onClick = { focusChannels() },
                        modifier = Modifier.focusRequester(groupRequesters.getValue(group.key)),
                        translucent = overlay,
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (!overlay) GuideHeader(windowStart) else FocusSurface(
                onClick = { onShowGroups(!showGroups) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val strip = categoryStrip(groups.map { it.key }, selected)
                    strip.forEachIndexed { index, group ->
                        val current = strip.size == 1 || index == 1
                        FLText(groupLabel(group), style = if (current) FireLiteTheme.typography.title else FireLiteTheme.typography.label,
                            color = if (current) FireLiteTheme.colors.accent else FireLiteTheme.colors.textSecondary.copy(alpha = 0.55f),
                            modifier = Modifier.weight(if (current) 1.3f else 1f), maxLines = 1)
                    }
                }
            }
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
                        if (overlay && event.key == Key.DirectionRight) {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                                openChannelHistory()
                            }
                            true
                        } else if (overlay && event.key == Key.DirectionLeft) {
                            if (event.type == KeyEventType.KeyDown) {
                                if (overlay && !showGroups) onShowGroups(true) else focusSelectedGroup()
                            }
                            true
                        } else false
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(if (overlay) CHANNEL_OVERLAY_GAP_DP.dp else 8.dp),
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
                                onFocused = { recordFocus(row, index) },
                                onDetails = { focusedProgramme = it },
                                onSelect = { cell ->
                                    scope.launch {
                                        val programme = cell?.programme
                                        val action = GuideTimeline.action(programme, now, row.channel.catchupDays, CatchupUrlBuilder.isSupported(row.channel),
                                            cell?.let { focusedTime.coerceIn(it.start, it.stop - 1) } ?: now, cell != null && row.channel.id in guideChannels)
                                        if (action != GuideAction.Details) library.zapContext(selected)?.let {
                                            if (action == GuideAction.Archive) player.openArchive(it, index, selected, programme!!.start)
                                            else player.openLiveProgramme(it, index, selected, row.channel, programme)
                                        }
                                    }
                                },
                                onMenu = { library.toggleFavorite(row.channel.streamUrl) },
                                modifier = Modifier.focusRequester(rowRequester(row)),
                            ) else ChannelRowItem(
                                row = row,
                                programme = programmesByChannel[row.channel.id]?.maxByOrNull { it.start },
                                now = now,
                                translucent = overlay,
                                modifier = Modifier.focusRequester(rowRequester(row)),
                                onFocused = { recordFocus(row, index) },
                                onClick = {
                                    scope.launch {
                                        library.zapContext(selected)?.let { player.open(it, index, selected) }
                                    }
                                },
                                onMenu = { openChannelHistory() },
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
            if (overlay) {
                detailGuide.next?.takeIf { pendingCategoryFocus == null }?.let {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FLText(stringResource(R.string.channel_next_programme, clockTime(it.start), it.title),
                            style = FireLiteTheme.typography.label, maxLines = 1, modifier = Modifier.weight(1f))
                        ImdbRating(it)
                    }
                }
            }
        }

        if (!overlay && !guideOnly) DetailPanel(row = focusedChannel, guide = detailGuide,
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
internal fun ChannelRowItem(
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
    val epg = (LocalContext.current.applicationContext as FireLiteApplication).container.epg
    val guideMinute = channelGuideQueryMinute(now)
    val guide by remember(channel.id, guideMinute) { epg.observeCurrentAndNextAt(channel.id, now) }
        .collectAsState(initial = CurrentAndNext(null, null))
    FocusSurface(
        onClick = onClick,
        onFocusChanged = { focused -> if (focused) onFocused() },
        onMenuKey = onMenu,
        background = SolidColor(if (translucent) Color.Transparent else FireLiteTheme.colors.surface),
        modifier = modifier
            .fillMaxWidth()
            .height(if (translucent) CHANNEL_OVERLAY_ROW_DP.dp else 80.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = if (translucent) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (translucent) 8.dp else 12.dp),
        ) {
            ChannelLogo(name = channel.name, logoUrl = channel.logoUrl, size = if (translucent) CHANNEL_OVERLAY_LOGO_DP.dp else 40.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (translucent) 2.dp else 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FLText(text = channel.name, style = if (translucent)
                        FireLiteTheme.typography.title.copy(fontSize = 14.sp, lineHeight = 18.sp) else FireLiteTheme.typography.title,
                        maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (CatchupUrlBuilder.isSupported(channel)) ArchiveIcon()
                    DeadChannelIcon(channel.streamUrl)
                }
                if (programme != null) {
                    if (translucent) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FLText(text = programme.title, style = FireLiteTheme.typography.label,
                            color = FireLiteTheme.colors.textSecondary, maxLines = 1, modifier = Modifier.weight(1f))
                        ImdbRating(programme)
                    } else {
                        FLText(text = programme.title, style = FireLiteTheme.typography.label,
                            color = FireLiteTheme.colors.textSecondary, maxLines = 1)
                        ImdbRating(programme)
                    }
                    ProgressBar(PlaybackTime.progress(now, programme.start, programme.stop))
                }
            }
            guide.next?.let { next ->
                FLText(
                    text = stringResource(R.string.channel_row_next_programme, clockTime(next.start), next.title),
                    style = FireLiteTheme.typography.label,
                    color = FireLiteTheme.colors.accent,
                    maxLines = 2,
                    modifier = Modifier.weight(0.65f),
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
                ImdbRating(current, showVotes = true)
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
