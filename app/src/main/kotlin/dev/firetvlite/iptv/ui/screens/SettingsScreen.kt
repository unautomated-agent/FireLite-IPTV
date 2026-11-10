package dev.firetvlite.iptv.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.library.ImportFailure
import dev.firetvlite.iptv.data.library.ImportState
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.LabeledTextField
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.pluralString
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.update.FailureReason
import dev.firetvlite.iptv.update.InstalledVersion
import dev.firetvlite.iptv.update.UpdateState
import dev.firetvlite.iptv.update.pendingRelease
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val WarningColor = Color(0xFFFFB74D)

/** Settings: the channel list (M3U address) and app updates. */
@Composable
fun SettingsScreen(
    installed: InstalledVersion,
    updateState: UpdateState,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstallSettings: () -> Unit,
    source: SourceEntity?,
    channelCount: Int,
    importState: ImportState,
    onSaveSource: (url: String, userAgent: String) -> Unit,
    onRefreshSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 24.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FLText(
            text = stringResource(R.string.nav_settings),
            style = FireLiteTheme.typography.display,
            modifier = Modifier.padding(start = 4.dp),
        )
        SourceSection(
            source = source,
            channelCount = channelCount,
            importState = importState,
            onSave = onSaveSource,
            onRefresh = onRefreshSource,
        )
        UpdateSection(
            installed = installed,
            state = updateState,
            onCheck = onCheckForUpdate,
            onInstall = onInstallUpdate,
            onOpenInstallSettings = onOpenInstallSettings,
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FireLiteTheme.shapes.large)
            .background(FireLiteTheme.colors.surface)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

// ------------------------------------------------------------------------------------ channel list

@Composable
private fun SourceSection(
    source: SourceEntity?,
    channelCount: Int,
    importState: ImportState,
    onSave: (url: String, userAgent: String) -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography

    var editing by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(source?.url.orEmpty()) }
    var agent by remember { mutableStateOf(source?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT) }
    var invalid by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(editing) { firstFocus.requestFocusWhenReady() }

    SettingsCard {
        FLText(text = stringResource(R.string.source_section), style = typography.headline)

        if (editing) {
            LabeledTextField(
                label = stringResource(R.string.source_url_label),
                value = url,
                onValueChange = {
                    url = it
                    invalid = false
                },
                modifier = Modifier.focusRequester(firstFocus),
            )
            LabeledTextField(
                label = stringResource(R.string.source_agent_label),
                value = agent,
                onValueChange = { agent = it },
            )
            if (invalid) {
                FLText(
                    text = stringResource(R.string.source_invalid_url),
                    style = typography.title,
                    color = WarningColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.source_save),
                    onClick = {
                        val trimmed = url.trim()
                        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                            onSave(trimmed, agent.trim().ifEmpty { LibraryRepository.DEFAULT_USER_AGENT })
                            editing = false
                        } else {
                            invalid = true
                        }
                    },
                )
                if (source != null) {
                    SecondaryButton(
                        text = stringResource(R.string.source_cancel),
                        onClick = {
                            url = source.url
                            agent = source.userAgent
                            invalid = false
                            editing = false
                        },
                    )
                }
            }
        } else {
            // Only the server name is shown: playlist addresses often contain a user name and password.
            FLText(
                text = if (source == null) {
                    stringResource(R.string.source_none)
                } else {
                    stringResource(R.string.source_server, Uri.parse(source.url).host ?: "?")
                },
                style = typography.body,
            )
            if (source != null) {
                val updated = if (source.refreshedAt > 0) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale("pl", "PL"))
                        .format(Date(source.refreshedAt))
                } else {
                    stringResource(R.string.source_never_refreshed)
                }
                FLText(
                    text = stringResource(
                        R.string.source_stats,
                        pluralString(R.plurals.channel_count, channelCount),
                        updated,
                    ),
                    style = typography.body,
                )
            }
            val status = importStatusText(importState)
            if (status.isNotEmpty()) {
                FLText(
                    text = status,
                    style = typography.title,
                    color = if (importState is ImportState.Failed) WarningColor else colors.accent,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton(
                    text = stringResource(if (source == null) R.string.source_add else R.string.source_change),
                    onClick = {
                        url = source?.url.orEmpty()
                        agent = source?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT
                        invalid = false
                        editing = true
                    },
                    modifier = Modifier.focusRequester(firstFocus),
                )
                if (source != null) {
                    SecondaryButton(
                        text = stringResource(R.string.source_refresh),
                        onClick = onRefresh,
                    )
                }
            }
        }
    }
}

@Composable
private fun importStatusText(state: ImportState): String = when (state) {
    ImportState.Idle -> ""
    ImportState.Running -> stringResource(R.string.import_running)
    is ImportState.Done -> stringResource(
        R.string.import_done,
        pluralString(R.plurals.channel_count, state.channelCount),
    )
    is ImportState.Failed -> stringResource(
        when (state.reason) {
            ImportFailure.Network -> R.string.import_error_network
            ImportFailure.EmptyPlaylist -> R.string.import_error_empty
            ImportFailure.NoSource -> R.string.import_error_no_source
        },
    )
}

// ------------------------------------------------------------------------------------ app updates

@Composable
private fun UpdateSection(
    installed: InstalledVersion,
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstallSettings: () -> Unit,
) {
    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography
    val statusColor = when (state) {
        is UpdateState.Failed -> WarningColor
        is UpdateState.Available -> colors.accent
        else -> colors.textSecondary
    }

    SettingsCard {
        FLText(text = stringResource(R.string.update_section), style = typography.headline)
        FLText(
            text = stringResource(R.string.update_installed, installed.versionName, installed.versionCode),
            style = typography.body,
        )
        val status = updateStatusText(state)
        if (status.isNotEmpty()) {
            FLText(text = status, style = typography.title, color = statusColor)
        }
        if (state is UpdateState.Downloading) {
            ProgressBar(progress = state.progress ?: 0f, modifier = Modifier.width(360.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(text = stringResource(R.string.update_check), onClick = onCheck)
            if (state.pendingRelease != null && state !is UpdateState.Downloading) {
                PrimaryButton(text = stringResource(R.string.update_install), onClick = onInstall)
            }
            if (state is UpdateState.Failed && state.reason == FailureReason.PermissionNeeded) {
                SecondaryButton(text = stringResource(R.string.update_open_settings), onClick = onOpenInstallSettings)
            }
        }
    }
}
