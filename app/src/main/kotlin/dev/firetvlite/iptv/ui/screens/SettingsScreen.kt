package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.update.FailureReason
import dev.firetvlite.iptv.update.InstalledVersion
import dev.firetvlite.iptv.update.UpdateState
import dev.firetvlite.iptv.update.pendingRelease

private val WarningColor = Color(0xFFFFB74D)

/** Settings. For now it holds the app update section; sources and playback options follow later. */
@Composable
fun SettingsScreen(
    installed: InstalledVersion,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestFocusWhenReady() }

    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography
    val statusColor = when (updateState) {
        is UpdateState.Failed -> WarningColor
        is UpdateState.Available -> colors.accent
        else -> colors.textSecondary
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 24.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FLText(
            text = stringResource(R.string.nav_settings),
            style = typography.display,
            modifier = Modifier.padding(start = 4.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(FireLiteTheme.shapes.large)
                .background(colors.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FLText(
                text = stringResource(R.string.update_section),
                style = typography.headline,
            )
            FLText(
                text = stringResource(
                    R.string.update_installed,
                    installed.versionName,
                    installed.versionCode,
                ),
                style = typography.body,
            )
            val status = updateStatusText(updateState)
            if (status.isNotEmpty()) {
                FLText(
                    text = status,
                    style = typography.title,
                    color = statusColor,
                )
            }
            if (updateState is UpdateState.Downloading) {
                ProgressBar(
                    progress = updateState.progress ?: 0f,
                    modifier = Modifier.width(360.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.update_check),
                    onClick = onCheckForUpdate,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                if (updateState.pendingRelease != null && updateState !is UpdateState.Downloading) {
                    PrimaryButton(
                        text = stringResource(R.string.update_install),
                        onClick = onInstallUpdate,
                    )
                }
                if (updateState is UpdateState.Failed && updateState.reason == FailureReason.PermissionNeeded) {
                    SecondaryButton(
                        text = stringResource(R.string.update_open_settings),
                        onClick = onOpenInstallSettings,
                    )
                }
            }
        }
    }
}
