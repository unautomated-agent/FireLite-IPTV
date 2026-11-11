package dev.firetvlite.iptv.ui.screens

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
internal fun ChannelHistoryList(target: ChannelTimelineTarget, player: PlayerViewModel, library: LibraryViewModel) {
    val now = target.frame.nowMs
    val earliest = ChannelTimeline.earliest(now, target.channel.catchupDays,
        CatchupUrlBuilder.isSupported(target.channel), target.liveWindowStart)
    val programmes by remember(target) {
        library.programmes(listOf(target.channel.id), earliest ?: now, now + 3 * ChannelTimeline.DAY)
    }.collectAsState(initial = emptyList())
    val timeline = remember(programmes, target) { ChannelTimeline.items(programmes.map { it.programme }, now, earliest) }
    val items = remember(timeline) { ChannelTimeline.history(timeline, earliest != null) }
    val nextProgramme = timeline.firstOrNull { it.future }
    var focusTime by remember(target) { mutableStateOf(target.frame.watchedMs) }
    var liveFocused by remember(target) { mutableStateOf(false) }
    var atStart by remember(target) { mutableStateOf(false) }
    val index = if (liveFocused) 0 else ChannelTimeline.historyFocusIndex(items, focusTime)
    val focused = items[index]
    val frame by player.frame.collectAsState()
    val playingIndex = if (target.playing) ChannelTimeline.historyFocusIndex(items, frame.watchedMs) else -1
    val requester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { requester.requestFocusWhenReady() }
    LaunchedEffect(index) { listState.scrollToItem(maxOf(0, index - 1)) }
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
                    Key.DirectionLeft, Key.DirectionRight, Key.MediaRewind -> true
                    Key.DirectionUp, Key.DirectionDown -> {
                        val delta = if (event.key == Key.DirectionUp) -1 else 1
                        val next = ChannelTimeline.move(index, delta, items.size)
                        atStart = next == index && delta > 0
                        liveFocused = items[next].live
                        focusTime = items[next].start
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (event.nativeKeyEvent.repeatCount == 0) player.playTimeline(focused)
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
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(items) { itemIndex, item ->
                    Column(Modifier.fillMaxWidth()
                        .background(if (itemIndex == index) colors.surfaceHigh.copy(alpha = 0.75f) else Color.Transparent)
                        .border(2.dp, if (itemIndex == index) Color.White else Color.Transparent).padding(10.dp)) {
                        FLText(timelineDay(item.start, now), style = FireLiteTheme.typography.label,
                            color = colors.accent)
                        FLText(if (item.live) stringResource(R.string.channel_timeline_live)
                            else "${clockTime(item.start)}–${clockTime(item.stop)}",
                            style = FireLiteTheme.typography.label,
                            color = if (item.future) colors.textSecondary.copy(alpha = 0.6f) else colors.textSecondary)
                        FLText((if (itemIndex == playingIndex) "▶ " else "") + timelineTitle(item),
                            style = FireLiteTheme.typography.body, maxLines = 2,
                            color = if (item.future) colors.textSecondary.copy(alpha = 0.6f) else colors.textPrimary)
                    }
                }
            }
            FLText("${timelineDay(focused.start, now)} · " +
                (if (focused.live) "" else "${clockTime(focused.start)}–${clockTime(focused.stop)} · ") + timelineTitle(focused),
                style = FireLiteTheme.typography.body, maxLines = 1)
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
    item.live -> stringResource(R.string.channel_timeline_live)
    item.programme != null -> item.programme.title
    else -> stringResource(R.string.channel_timeline_hour)
}

@Composable
private fun timelineDay(time: Long, now: Long): String {
    val format = remember { SimpleDateFormat("dd.MM.yyyy", Locale("pl", "PL")) }
    val day = format.format(Date(time))
    val yesterday = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -1) }
    return when (day) {
        format.format(Date(now)) -> stringResource(R.string.channel_timeline_today)
        format.format(yesterday.time) -> stringResource(R.string.channel_timeline_yesterday)
        else -> day
    }
}
