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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.NavIcon
import dev.firetvlite.iptv.ui.components.NavIconKind
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.pluralString
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

private val groupLabels = listOf(
    R.string.sample_group_all,
    R.string.sample_group_news,
    R.string.sample_group_sport,
    R.string.sample_group_movies,
    R.string.sample_group_kids,
    R.string.sample_group_music,
)

private const val SAMPLE_CHANNEL_COUNT = 30

/** Demo channels of a group; group 0 holds all of them. Real data arrives with the source packages. */
private fun sampleChannels(group: Int): List<Int> =
    (1..SAMPLE_CHANNEL_COUNT).filter { group == 0 || it % (groupLabels.size - 1) == group - 1 }

/**
 * Live TV browser: groups, channels with "now" and progress, and a preview panel.
 * The preview is off by default; it will be an opt-in setting so no second stream is ever opened.
 */
@Composable
fun LiveTvScreen(modifier: Modifier = Modifier) {
    var selectedGroup by rememberSaveable { mutableIntStateOf(0) }
    val firstGroupFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstGroupFocus.requestFocusWhenReady() }

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 24.dp, top = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        GroupColumn(
            selectedGroup = selectedGroup,
            onGroupFocused = { selectedGroup = it },
            firstGroupFocus = firstGroupFocus,
            modifier = Modifier.width(188.dp),
        )
        ChannelColumn(
            group = selectedGroup,
            modifier = Modifier.weight(1f),
        )
        PreviewPanel(modifier = Modifier.width(236.dp))
    }
}

@Composable
private fun GroupColumn(
    selectedGroup: Int,
    onGroupFocused: (Int) -> Unit,
    firstGroupFocus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        FLText(
            text = stringResource(R.string.live_groups),
            style = FireLiteTheme.typography.headline,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(groupLabels) { index, labelRes ->
                val selected = index == selectedGroup
                FocusSurface(
                    onClick = { onGroupFocused(index) },
                    onFocusChanged = { focused -> if (focused) onGroupFocused(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(firstGroupFocus) else Modifier),
                    background = SolidColor(
                        if (selected) FireLiteTheme.colors.surfaceHigh else FireLiteTheme.colors.surface,
                    ),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        FLText(
                            text = stringResource(labelRes),
                            style = FireLiteTheme.typography.title,
                            color = if (selected) FireLiteTheme.colors.accent else FireLiteTheme.colors.textPrimary,
                            maxLines = 1,
                        )
                        FLText(
                            text = pluralString(R.plurals.channel_count, sampleChannels(index).size),
                            style = FireLiteTheme.typography.label,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelColumn(
    group: Int,
    modifier: Modifier = Modifier,
) {
    val channels = remember(group) { sampleChannels(group) }
    Column(modifier = modifier.fillMaxHeight()) {
        FLText(
            text = stringResource(R.string.live_channels),
            style = FireLiteTheme.typography.headline,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = channels, key = { it }) { number ->
                ChannelRow(number = number)
            }
        }
    }
}

@Composable
private fun ChannelRow(number: Int) {
    FocusSurface(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FLText(
                text = number.toString(),
                style = FireLiteTheme.typography.title,
                color = FireLiteTheme.colors.accent,
                modifier = Modifier.width(34.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                FLText(
                    text = stringResource(R.string.sample_channel_name, number),
                    style = FireLiteTheme.typography.title,
                    maxLines = 1,
                )
                FLText(
                    text = stringResource(
                        R.string.live_now,
                        stringResource(R.string.sample_program_title, number),
                    ),
                    style = FireLiteTheme.typography.label,
                    maxLines = 1,
                )
            }
            ProgressBar(
                progress = 0.1f + 0.027f * number,
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

@Composable
private fun PreviewPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(FireLiteTheme.shapes.large)
            .background(FireLiteTheme.colors.surface)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FLText(
            text = stringResource(R.string.live_preview_title),
            style = FireLiteTheme.typography.headline,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NavIcon(
                    kind = NavIconKind.Live,
                    tint = FireLiteTheme.colors.textSecondary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                FLText(
                    text = stringResource(R.string.live_preview_off),
                    style = FireLiteTheme.typography.title,
                )
                Spacer(modifier = Modifier.height(4.dp))
                FLText(
                    text = stringResource(R.string.live_preview_hint),
                    style = FireLiteTheme.typography.body,
                )
            }
        }
        FLText(
            text = stringResource(R.string.sample_notice),
            style = FireLiteTheme.typography.label,
        )
    }
}
