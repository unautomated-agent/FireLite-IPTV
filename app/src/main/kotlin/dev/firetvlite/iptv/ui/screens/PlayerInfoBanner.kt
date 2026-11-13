package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.epg.CurrentAndNext
import dev.firetvlite.iptv.playback.PlaybackFrame
import dev.firetvlite.iptv.playback.PlaybackTime
import dev.firetvlite.iptv.playback.SeekPreview
import dev.firetvlite.iptv.playback.SeekTarget
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.ArchiveIcon
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.ImdbRating
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.minuteClock
import dev.firetvlite.iptv.ui.components.programmeTimes
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import kotlin.math.abs

@Composable
internal fun PlayerInfoBanner(channel: ChannelEntity?, frame: PlaybackFrame, guide: CurrentAndNext,
    expanded: Boolean, scroll: ScrollState, archive: Boolean, seekPreview: SeekPreview? = null) {
    val colors = FireLiteTheme.colors
    val type = FireLiteTheme.typography
    val now by minuteClock()
    Column(Modifier.fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (expanded) 0f else 0.7f))))
        .padding(start = 32.dp, end = 32.dp, top = 36.dp, bottom = 24.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (expanded) Color.Black.copy(alpha = 0.7f) else Color(0xE615202D))
            .padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ChannelLogo(channel?.name.orEmpty(), channel?.logoUrl?.takeIf { it.isNotBlank() }, size = 64.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FLText(channel?.name.orEmpty(), style = type.title, maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false))
                        if (archive) ArchiveIcon(description = stringResource(R.string.player_archive_mode))
                    }
                    FLText(channel?.groupName.orEmpty(), style = type.label, color = colors.textSecondary, maxLines = 1)
                }
                if (!archive) FLText(stringResource(R.string.player_live_mode), style = type.label,
                    color = colors.accent, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(colors.accent.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp))
                FLText(clockTime(now), style = type.headline)
            }
            seekPreview?.let { pending ->
                val offset = (if (pending.offsetMs < 0) "−" else "+") + formatClock(abs(pending.offsetMs))
                FLText(stringResource(R.string.player_seek_preview, offset, pending.multiplier),
                    style = type.title, color = colors.accent)
            }
            val current = guide.current
            FLText(current?.title ?: "Brak informacji o programie",
                style = type.headline.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold), maxLines = 2)
            if (current != null) {
                ImdbRating(current, showVotes = expanded)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FLText(programmeTimes(current), style = type.body)
                    FLText("jeszcze ${((current.stop - frame.watchedMs).coerceAtLeast(0) + 59_999) / 60_000} min",
                        style = type.label, color = colors.textSecondary)
                }
                val target = seekPreview?.let { SeekTarget.time(frame, current.start, current.stop, it.offsetMs,
                    channel?.catchupDays ?: 0, channel?.let(CatchupUrlBuilder::isSupported) == true) }
                ProgressBar(PlaybackTime.progress(frame.watchedMs, current.start, current.stop),
                    liveProgress = target?.let { PlaybackTime.progress(it, current.start, current.stop) })
                target?.let { FLText(stringResource(R.string.player_seek_target, clockTime(it)),
                    style = type.label, color = colors.accent) }
            }
            guide.next?.let { FLText(stringResource(R.string.channel_next_programme, clockTime(it.start), it.title),
                style = type.body, maxLines = 1, color = colors.accent) }
            current?.description?.takeIf { it.isNotBlank() }?.let { description ->
                FLText(description, style = type.body, maxLines = if (expanded) Int.MAX_VALUE else 3, color = colors.textSecondary,
                    modifier = if (expanded) Modifier.heightIn(max = 260.dp).verticalScroll(scroll) else Modifier)
            }
        }
    }
}
