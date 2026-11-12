package dev.firetvlite.iptv.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.db.SavedSource
import dev.firetvlite.iptv.data.epg.EpgState
import dev.firetvlite.iptv.data.library.ImportFailure
import dev.firetvlite.iptv.data.library.ImportState
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.library.SourceDiagnostics
import kotlinx.coroutines.flow.Flow
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
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
    sources: List<SavedSource>,
    importState: ImportState,
    epgStates: Map<Long, EpgState>,
    onSaveSource: (url: String, userAgent: String, epgUrl: String, id: Long?) -> Unit,
    onActivateSource: (Long) -> Unit,
    onDeleteSource: (Long) -> Unit,
    onRefreshSource: () -> Unit,
    diagnostics: Flow<SourceDiagnostics>,
    canWatch: Boolean,
    onWatch: () -> Unit,
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
            source = source, sources = sources, importState = importState, epgStates = epgStates,
            onSave = onSaveSource, onRefresh = onRefreshSource, onActivate = onActivateSource, onDelete = onDeleteSource,
        )
        if (canWatch) PrimaryButton(stringResource(R.string.settings_watch_live), onWatch)
        SettingsCard {
            val preferences = (LocalContext.current.applicationContext as FireLiteApplication).container.clockPreferences
            val visible by preferences.visible.collectAsState()
            FLText(stringResource(R.string.settings_appearance), style = FireLiteTheme.typography.title)
            PrimaryButton(stringResource(if (visible) R.string.settings_clock_on else R.string.settings_clock_off),
                { preferences.setVisible(!visible) })
        }
        SettingsCard { OmdbSettings() }
        SettingsCard { DiagnosticsSection(source, source?.id?.let { epgStates[it] }, diagnostics) }
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
@OptIn(ExperimentalFoundationApi::class)
private fun SourceSection(
    source: SourceEntity?,
    sources: List<SavedSource>,
    importState: ImportState,
    epgStates: Map<Long, EpgState>,
    onSave: (url: String, userAgent: String, epgUrl: String, id: Long?) -> Unit,
    onRefresh: () -> Unit,
    onActivate: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val colors = FireLiteTheme.colors
    val typography = FireLiteTheme.typography

    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<SourceEntity?>(null) }
    var url by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf(LibraryRepository.DEFAULT_USER_AGENT) }
    var epgUrl by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var invalidEpg by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val agentFocus = remember { FocusRequester() }
    val epgFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    val firstBounds = remember { BringIntoViewRequester() }
    val rowFocus = remember { mutableMapOf<Long, FocusRequester>() }
    val rowBounds = remember { mutableMapOf<Long, BringIntoViewRequester>() }
    var pendingFocus by remember { mutableStateOf<SavedSourceFocusRequest?>(null) }
    val sourceIds = sources.map { it.source.id }
    LaunchedEffect(editing) {
        if (deleting == null) firstFocus.requestFocusWhenReady()
    }
    LaunchedEffect(pendingFocus, sourceIds, source?.id, editing, deleting) {
        if (editing || deleting != null) return@LaunchedEffect
        when (val target = pendingFocus?.target(sourceIds, source?.id)) {
            is SavedSourceFocusTarget.Row -> {
                rowFocus.getValue(target.id).requestFocusWhenReady()
                rowBounds.getValue(target.id).bringIntoView()
            }
            SavedSourceFocusTarget.Add -> {
                firstFocus.requestFocusWhenReady()
                firstBounds.bringIntoView()
            }
            null -> return@LaunchedEffect
        }
        pendingFocus = null
    }
    BackHandler(enabled = editing) { editing = false }

    fun openForm(existing: SourceEntity?) {
        pendingFocus = null
        editingId = existing?.id
        url = existing?.url.orEmpty()
        agent = existing?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT
        epgUrl = existing?.epgUrl.orEmpty()
        invalid = false
        invalidEpg = false
        editing = true
    }

    deleting?.let { entry ->
        val confirmFocus = remember { FocusRequester() }
        fun dismissDelete() {
            pendingFocus = SavedSourceFocusRequest.Return(entry.id)
            deleting = null
        }
        Dialog(onDismissRequest = { dismissDelete() }) {
            LaunchedEffect(Unit) { confirmFocus.requestFocusWhenReady() }
            SettingsCard {
                FLText(
                    text = stringResource(R.string.source_delete_confirm, entry.name, entry.id),
                    style = typography.headline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.source_delete),
                        onClick = {
                            val index = sourceIds.indexOf(entry.id)
                            val neighbours = if (index < 0) sourceIds
                                else sourceIds.drop(index + 1) + sourceIds.take(index).asReversed()
                            pendingFocus = SavedSourceFocusRequest.Remove(entry.id, neighbours)
                            onDelete(entry.id)
                            deleting = null
                        },
                        modifier = Modifier.focusRequester(confirmFocus),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.source_cancel),
                        onClick = { dismissDelete() },
                    )
                }
            }
        }
    }

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
                modifier = Modifier.focusRequester(firstFocus).focusProperties { down = agentFocus },
            )
            LabeledTextField(
                label = stringResource(R.string.source_agent_label),
                value = agent,
                onValueChange = { agent = it },
                modifier = Modifier.focusRequester(agentFocus).focusProperties {
                    up = firstFocus
                    down = epgFocus
                },
            )
            LabeledTextField(
                label = stringResource(R.string.source_epg_label),
                value = epgUrl,
                onValueChange = { epgUrl = it; invalidEpg = false },
                modifier = Modifier.focusRequester(epgFocus).focusProperties {
                    up = agentFocus
                    down = saveFocus
                },
            )
            if (invalid) {
                FLText(
                    text = stringResource(R.string.source_invalid_url),
                    style = typography.title,
                    color = WarningColor,
                )
            }
            if (invalidEpg) {
                FLText(
                    text = stringResource(R.string.source_epg_invalid),
                    style = typography.title,
                    color = WarningColor,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.source_save),
                    onClick = {
                        val trimmed = url.trim()
                        val uri = Uri.parse(trimmed)
                        invalid = uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()
                        invalidEpg = epgUrl.isNotBlank() && epgUrl.split(',').any {
                            val epg = Uri.parse(it.trim())
                            epg.scheme !in listOf("http", "https") || epg.host.isNullOrEmpty()
                        }
                        if (!invalid && !invalidEpg) {
                            onSave(trimmed, agent.trim().ifEmpty { LibraryRepository.DEFAULT_USER_AGENT }, epgUrl.trim(), editingId)
                            editing = false
                        }
                    },
                    modifier = Modifier.focusRequester(saveFocus).focusProperties { up = epgFocus },
                )
                SecondaryButton(
                    text = stringResource(R.string.source_cancel),
                    onClick = { editing = false },
                    modifier = Modifier.focusProperties { up = epgFocus },
                )
            }
        } else {
            if (sources.isEmpty()) {
                FLText(text = stringResource(R.string.source_none), style = typography.body)
            }
            sources.forEach { saved ->
                key(saved.source.id) {
                    val entry = saved.source
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FocusSurface(
                            onClick = {
                                pendingFocus = SavedSourceFocusRequest.Activate(entry.id)
                                onActivate(entry.id)
                            },
                            modifier = Modifier.weight(1f)
                                .focusRequester(rowFocus.getOrPut(entry.id) { FocusRequester() })
                                .bringIntoViewRequester(rowBounds.getOrPut(entry.id) { BringIntoViewRequester() }),
                            focusedScale = 1f,
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FLText(
                                    text = stringResource(
                                        R.string.source_entry, entry.name, entry.id, Uri.parse(entry.url).host ?: "?",
                                    ),
                                    style = typography.title,
                                    maxLines = 1,
                                )
                                if (entry.id == source?.id) {
                                    FLText(
                                        text = stringResource(R.string.source_active),
                                        style = typography.label,
                                        color = colors.accent,
                                    )
                                }
                                val updated = if (entry.refreshedAt > 0) {
                                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale("pl", "PL"))
                                        .format(Date(entry.refreshedAt))
                                } else stringResource(R.string.source_never_refreshed)
                                FLText(
                                    text = stringResource(
                                        R.string.source_stats, pluralString(R.plurals.channel_count, saved.channelCount), updated,
                                    ),
                                    style = typography.body,
                                )
                                FLText(
                                    text = epgStatusText(entry, epgStates[entry.id]),
                                    style = typography.body,
                                    color = if (epgStates[entry.id] is EpgState.Failed) WarningColor else colors.textSecondary,
                                )
                            }
                        }
                        SecondaryButton(
                            text = stringResource(R.string.source_delete),
                            onClick = { deleting = entry },
                        )
                    }
                }
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
                    text = stringResource(R.string.source_add),
                    onClick = { openForm(null) },
                    modifier = Modifier.focusRequester(firstFocus).bringIntoViewRequester(firstBounds),
                )
                if (source != null) {
                    SecondaryButton(
                        text = stringResource(R.string.source_change),
                        onClick = { openForm(source) },
                    )
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
private fun epgStatusText(source: SourceEntity, state: EpgState?): String = when {
    state == EpgState.Running -> stringResource(R.string.epg_running)
    state is EpgState.Failed -> stringResource(R.string.epg_failed)
    source.epgRefreshedAt > 0 -> stringResource(
        R.string.epg_stats,
        pluralString(R.plurals.programme_count, source.epgProgrammeCount),
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale("pl", "PL"))
            .format(Date(source.epgRefreshedAt)),
    )
    source.epgUrl.isNotBlank() || source.discoveredEpgUrls.isNotBlank() -> stringResource(R.string.epg_waiting)
    else -> stringResource(R.string.epg_none)
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
