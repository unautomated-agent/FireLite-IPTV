package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelRow
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal val GuideNameWidth = 108.dp

@Composable
internal fun GuideHeader(start: Long) {
    FLText(SimpleDateFormat("EEEE dd.MM.yyyy", Locale("pl", "PL")).format(Date(start)),
        style = FireLiteTheme.typography.body, modifier = Modifier.padding(bottom = 4.dp))
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        FLText(stringResource(R.string.live_channels), style = FireLiteTheme.typography.label, modifier = Modifier.width(GuideNameWidth))
        BoxWithConstraints(Modifier.weight(1f).height(28.dp).clipToBounds()) {
            val first = ((start + GuideTimeline.HOUR / 2 - 1) / (GuideTimeline.HOUR / 2)) * (GuideTimeline.HOUR / 2)
            for (tick in first..(start + GuideTimeline.WINDOW) step GuideTimeline.HOUR / 2) {
                FLText(clockTime(tick), style = FireLiteTheme.typography.label,
                    modifier = Modifier.offset(x = maxWidth * GuideTimeline.fraction(tick, start)).width(52.dp))
            }
        }
    }
}

@Composable
internal fun ProgrammeGridRow(
    row: ChannelRow,
    programmes: List<ProgrammeEntity>,
    hasGuide: Boolean,
    start: Long,
    now: Long,
    focusedTime: Long,
    programmeFocus: Boolean,
    onProgrammeFocus: (Boolean) -> Unit,
    onTime: (Long) -> Unit,
    onHorizontalEdge: (Boolean, Long) -> Unit,
    onVertical: (Int) -> Unit,
    onGroups: () -> Unit,
    onFocused: () -> Unit,
    onDetails: (ProgrammeEntity?) -> Unit,
    onSelect: (GuideCell?) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cells = remember(programmes, start) { GuideTimeline.cells(programmes, start) }
    val index = GuideTimeline.focusIndex(cells, focusedTime)
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, programmeFocus, cells, index) {
        if (focused) onDetails(if (programmeFocus) cells.getOrNull(index)?.programme else null)
    }
    val colors = FireLiteTheme.colors
    Row(modifier.fillMaxWidth().height(80.dp)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
        .onPreviewKeyEvent { event ->
            if (event.key == Key.Menu) {
                if (event.type == KeyEventType.KeyUp) onMenu()
                true
            } else if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionUp -> { onVertical(-1); true }
                Key.DirectionDown -> { onVertical(1); true }
                Key.DirectionLeft -> {
                    if (!programmeFocus) onGroups()
                    else if (index > 0) onTime(cells[index - 1].start)
                    else onHorizontalEdge(false, GuideTimeline.adjacentTime(cells[index], false))
                    true
                }
                Key.DirectionRight -> {
                    if (!programmeFocus) onProgrammeFocus(true)
                    else if (index < cells.lastIndex) onTime(cells[index + 1].start)
                    else onHorizontalEdge(true, GuideTimeline.adjacentTime(cells[index], true))
                    true
                }
                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                    if (event.nativeKeyEvent.repeatCount == 0) onSelect(if (programmeFocus) cells.getOrNull(index) else null)
                    true
                }
                else -> false
            }
        }.focusable(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(GuideNameWidth).fillMaxHeight().background(colors.surfaceHigh)
            .border(2.dp, if (focused && !programmeFocus) Color.White else Color.Transparent)
            .padding(6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChannelLogo(row.channel.name, row.channel.logoUrl, size = 28.dp)
            FLText((if (row.isFavorite) "♥ " else "") + row.channel.name,
                style = FireLiteTheme.typography.label, maxLines = 2, modifier = Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            cells.forEachIndexed { cellIndex, cell ->
                val cellWidth = maxWidth * (GuideTimeline.fraction(cell.stop, start) - GuideTimeline.fraction(cell.start, start))
                val selected = focused && programmeFocus && index == cellIndex
                val playable = GuideTimeline.action(cell.programme, now, row.channel.catchupDays,
                    CatchupUrlBuilder.isSupported(row.channel), focusedTime.coerceIn(cell.start, cell.stop - 1), hasGuide) != GuideAction.Details
                Column(Modifier.offset(x = maxWidth * GuideTimeline.fraction(cell.start, start))
                    .width(cellWidth)
                    .fillMaxHeight().padding(horizontal = 1.dp)
                    .background(if (selected) colors.accent.copy(alpha = 0.18f) else colors.surface)
                    .border(2.dp, if (selected) Color.White else Color.Transparent).padding(6.dp),
                    verticalArrangement = Arrangement.Center) {
                    if (cellWidth >= 40.dp) {
                        cell.programme?.let { FLText(clockTime(it.start), style = FireLiteTheme.typography.label,
                            color = if (playable) colors.textSecondary else colors.textSecondary.copy(alpha = 0.65f), maxLines = 1) }
                        FLText(cell.programme?.title ?: stringResource(R.string.guide_no_programme),
                            style = FireLiteTheme.typography.label,
                            color = if (playable) colors.textPrimary else colors.textSecondary.copy(alpha = 0.65f), maxLines = 2)
                    }
                }
            }
        }
    }
}
