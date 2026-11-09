package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.UpdateBanner
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.update.UpdateState
import dev.firetvlite.iptv.update.pendingRelease

/**
 * Start screen: a hero banner followed by horizontal rows.
 *
 * A banner on top offers a pending app update. All other content is demo data until sources are
 * connected; the hero says so.
 */
@Composable
fun HomeScreen(
    updateState: UpdateState,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heroFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { heroFocus.requestFocusWhenReady() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 24.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (updateState.pendingRelease != null) {
            item(key = "update") {
                UpdateBanner(state = updateState, onInstall = onInstallUpdate)
            }
        }
        item(key = "hero") {
            HeroBanner(heroFocus = heroFocus)
        }
        item(key = "continue") {
            MediaRow(title = stringResource(R.string.home_row_continue)) {
                items(count = 8, key = { it }) { index ->
                    PosterCard(
                        title = stringResource(R.string.sample_movie_title, index + 1),
                        index = index,
                        progress = if (index < 5) 0.15f + 0.15f * index else null,
                    )
                }
            }
        }
        item(key = "channels") {
            MediaRow(title = stringResource(R.string.home_row_recent_channels)) {
                items(count = 10, key = { it }) { index ->
                    ChannelCard(
                        number = index + 1,
                        name = stringResource(R.string.sample_channel_name, index + 1),
                        now = stringResource(R.string.sample_program_title, index + 1),
                        progress = 0.1f + 0.08f * index,
                        index = index,
                    )
                }
            }
        }
        item(key = "movies") {
            MediaRow(title = stringResource(R.string.home_row_new_movies)) {
                items(count = 10, key = { it }) { index ->
                    PosterCard(
                        title = stringResource(R.string.sample_movie_title, index + 9),
                        index = index + 3,
                        progress = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(heroFocus: FocusRequester) {
    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(214.dp)
            .clip(FireLiteTheme.shapes.large)
            .background(
                Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF0F2447), colors.background)),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 32.dp)
                .width(440.dp),
        ) {
            FLText(
                text = stringResource(R.string.home_hero_eyebrow),
                style = typography.label,
                color = colors.accent,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FLText(
                text = stringResource(R.string.sample_hero_title),
                style = typography.display,
                maxLines = 2,
            )
            Spacer(modifier = Modifier.height(6.dp))
            FLText(
                text = stringResource(R.string.sample_hero_meta),
                style = typography.body,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(
                        R.string.home_hero_resume,
                        stringResource(R.string.sample_hero_resume_time),
                    ),
                    onClick = {},
                    modifier = Modifier.focusRequester(heroFocus),
                )
                SecondaryButton(
                    text = stringResource(R.string.home_hero_details),
                    onClick = {},
                )
            }
        }
        FLText(
            text = stringResource(R.string.sample_notice),
            style = typography.label,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
        )
    }
}

/** A titled horizontal row. The vertical padding leaves room for the focus scale-up, which a lazy list would clip. */
@Composable
private fun MediaRow(
    title: String,
    content: LazyListScope.() -> Unit,
) {
    Column {
        FLText(
            text = title,
            style = FireLiteTheme.typography.headline,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            content = content,
        )
    }
}

@Composable
private fun PosterCard(
    title: String,
    index: Int,
    progress: Float?,
) {
    FocusSurface(
        onClick = {},
        modifier = Modifier.size(width = 118.dp, height = 176.dp),
        background = sampleBrush(index),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                FLText(
                    text = title,
                    style = FireLiteTheme.typography.label,
                    color = FireLiteTheme.colors.textPrimary,
                    maxLines = 2,
                )
            }
            if (progress != null) {
                ProgressBar(progress = progress)
            }
        }
    }
}

@Composable
private fun ChannelCard(
    number: Int,
    name: String,
    now: String,
    progress: Float,
    index: Int,
) {
    FocusSurface(
        onClick = {},
        modifier = Modifier.size(width = 168.dp, height = 96.dp),
        background = sampleBrush(index + 2),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FLText(
                    text = number.toString(),
                    style = FireLiteTheme.typography.label,
                    color = FireLiteTheme.colors.accent,
                )
                Spacer(modifier = Modifier.width(8.dp))
                FLText(
                    text = name,
                    style = FireLiteTheme.typography.title,
                    maxLines = 1,
                )
            }
            Column {
                FLText(
                    text = now,
                    style = FireLiteTheme.typography.label,
                    color = FireLiteTheme.colors.textPrimary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.height(6.dp))
                ProgressBar(progress = progress)
            }
        }
    }
}
