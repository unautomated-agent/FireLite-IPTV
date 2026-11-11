package dev.firetvlite.iptv.ui.screens

import android.widget.Toast
import dev.firetvlite.iptv.ui.components.ImdbRating

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.playback.ChannelTimeline
import dev.firetvlite.iptv.playback.TimelineItem
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.ChannelTimelineTarget
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel

@Composable
internal fun ChannelHistoryList(target: ChannelTimelineTarget, player: PlayerViewModel, library: LibraryViewModel) {
    val now = target.frame.nowMs
    val earliest = ChannelTimeline.earliest(now, target.channel.catchupDays,
        CatchupUrlBuilder.isSupported(target.channel), target.liveWindowStart)
    val programmes by remember(target) {
        library.programmes(listOf(target.channel.id), earliest ?: now, Long.MAX_VALUE)
    }.collectAsState(initial = emptyList())
    val timeline = remember(programmes, target) { ChannelTimeline.items(programmes.map { it.programme }, now, earliest) }
    val items = remember(timeline) { ChannelTimeline.history(timeline, earliest != null) }
    val nextProgramme = timeline.firstOrNull { it.future }
    var focus by remember(target) { mutableStateOf(HistoryFocus(now, live = true)) }
    var atStart by remember(target) { mutableStateOf(false) }
    val index = focus.index(items)
    val focused = items[index]
    val dayLabel = HistoryNavigation.dayLabel(focused.start, now,
        stringResource(R.string.channel_timeline_today), stringResource(R.string.channel_timeline_yesterday),
        stringResource(R.string.channel_timeline_tomorrow))
    val context = LocalContext.current
    val frame by player.frame.collectAsState()
    val playingIndex = if (target.playing) ChannelTimeline.historyFocusIndex(items, frame.watchedMs) else -1
    val requester = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = index)
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }
    LaunchedEffect(index, items.size) {
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
        if (row == null || row.offset < layout.viewportStartOffset || row.offset + row.size > layout.viewportEndOffset) {
            listState.animateScrollToItem(index)
        }
    }
    val colors = FireLiteTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        Column(Modifier.width(480.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xBF101820), Color(0xD2101820))))
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Menu) {
                    if (event.type == KeyEventType.KeyUp) player.closeTimeline()
                    true
                } else if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> true
                    Key.MediaRewind, Key.MediaFastForward -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            focus = HistoryNavigation.jumpDay(items, focus,
                                if (event.key == Key.MediaRewind) -1 else 1, now)
                            atStart = false
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        val delta = if (event.key == Key.DirectionUp) -1 else 1
                        val next = ChannelTimeline.move(index, delta, items.size)
                        atStart = next == index && delta < 0
                        focus = HistoryFocus(items[next].start, items[next].live)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.nativeKeyEvent.repeatCount == 0) HistoryNavigation.select(focused, { time ->
                            Toast.makeText(context, context.getString(R.string.channel_timeline_future, clockTime(time)), Toast.LENGTH_SHORT).show()
                        }, player::playTimeline)
                        true
                    }
                    else -> false
                }
            }.focusable().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FLText(stringResource(R.string.channel_timeline_title, target.channel.name), style = FireLiteTheme.typography.title)
            nextProgramme?.let {
                FLText(stringResource(R.string.epg_next, "${clockTime(it.start)}–${clockTime(it.stop)}", timelineTitle(it)),
                    style = FireLiteTheme.typography.label, maxLines = 2, color = colors.textSecondary.copy(alpha = 0.6f))
            }
            FLText(dayLabel, style = FireLiteTheme.typography.title,
                color = colors.accent)
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(items, key = { _, item -> if (item.live) "live" else "time:${item.start}" }) { itemIndex, item ->
                    Column(Modifier.fillMaxWidth()
                        .background(if (itemIndex == index) colors.surfaceHigh.copy(alpha = 0.75f) else Color.Transparent)
                        .border(2.dp, if (itemIndex == index) Color.White else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 7.dp)) {
                        FLText((if (itemIndex == playingIndex) "▶ " else "") +
                            (if (item.future) stringResource(R.string.channel_timeline_upcoming, clockTime(item.start), timelineTitle(item))
                            else (if (item.live) "" else "${clockTime(item.start)}  ") + timelineTitle(item)),
                            style = FireLiteTheme.typography.label, maxLines = 1,
                            color = if (item.future) colors.textSecondary.copy(alpha = 0.6f) else colors.textPrimary)
                    }
                }
            }
            FLText("$dayLabel · " +
                (if (focused.live) "" else "${clockTime(focused.start)}–${clockTime(focused.stop)} · ") + timelineTitle(focused),
                style = FireLiteTheme.typography.body, maxLines = 1)
            ImdbRating(focused.programme)
            FLText(focused.programme?.description?.takeIf { it.isNotBlank() }
                ?: "",
                style = FireLiteTheme.typography.label, maxLines = 2)
            FLText(stringResource(when {
                atStart -> R.string.channel_timeline_start
                earliest == null -> R.string.channel_timeline_unavailable
                else -> R.string.channel_timeline_hint
            }), style = FireLiteTheme.typography.label, color = colors.accent)
        }
    }
}

@Composable
private fun timelineTitle(item: TimelineItem): String = when {
    item.live -> stringResource(R.string.channel_timeline_live) + (item.programme?.title?.let { " · $it" } ?: "")
    item.programme != null -> item.programme.title
    else -> stringResource(R.string.channel_timeline_hour)
}
