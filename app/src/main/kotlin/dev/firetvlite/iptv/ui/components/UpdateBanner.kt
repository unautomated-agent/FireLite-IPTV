package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.screens.updateStatusText
import dev.firetvlite.iptv.update.UpdateState
import dev.firetvlite.iptv.update.pendingRelease
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/**
 * Shown at the top of the home screen while a newer build is known: one press of OK installs it.
 * Draws nothing when there is no pending update.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.pendingRelease == null) return
    val downloading = state as? UpdateState.Downloading

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(FireLiteTheme.shapes.large)
            .background(Brush.horizontalGradient(listOf(Color(0xFF16466B), Color(0xFF0F2447))))
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            FLText(
                text = stringResource(R.string.update_banner_title),
                style = FireLiteTheme.typography.headline,
            )
            FLText(
                text = updateStatusText(state),
                style = FireLiteTheme.typography.body,
                maxLines = 2,
            )
            if (downloading != null) {
                Spacer(modifier = Modifier.height(8.dp))
                ProgressBar(progress = downloading.progress ?: 0f)
            }
        }
        if (downloading == null) {
            PrimaryButton(
                text = stringResource(R.string.update_banner_action),
                onClick = onInstall,
            )
        }
    }
}
