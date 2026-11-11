package dev.firetvlite.iptv.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.CrashReportStore
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.db.SavedSource
import dev.firetvlite.iptv.data.library.ImportFailure
import dev.firetvlite.iptv.data.library.ImportState
import dev.firetvlite.iptv.data.library.LibraryRepository
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    onSaveSource: (url: String, userAgent: String, id: Long?) -> Unit,
    onActivateSource: (Long) -> Unit,
    onDeleteSource: (Long) -> Unit,
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
            sources = sources,
            importState = importState,
            onSave = onSaveSource,
            onRefresh = onRefreshSource,
            onActivate = onActivateSource,
            onDelete = onDeleteSource,
        )
        UpdateSection(
            installed = installed,
            state = updateState,
            onCheck = onCheckForUpdate,
            onInstall = onInstallUpdate,
            onOpenInstallSettings = onOpenInstallSettings,
        )
        CrashSection()
    }
}

@Composable
private fun CrashSection() {
    val context = LocalContext.current
    val store = remember(context) { CrashReportStore(File(context.filesDir, "last-crash.txt")) }
    var report by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { report = withContext(Dispatchers.IO) { store.read() } }
    val text = report ?: return
    SettingsCard {
        FLText(text = stringResource(R.string.crash_section), style = FireLiteTheme.typography.headline)
        FLText(text = stringResource(R.string.crash_scroll_hint), style = FireLiteTheme.typography.label)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusSurface(
                onClick = {},
                focusedScale = 1f,
                modifier = Modifier.weight(1f).height(180.dp).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionDown || event.key == Key.DirectionUp)
                    ) {
                        scope.launch { scroll.animateScrollBy(if (event.key == Key.DirectionDown) 120f else -120f) }
                        true
                    } else false
                },
            ) {
                FLText(
                    text = text,
                    style = FireLiteTheme.typography.body,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp),
                )
            }
            SecondaryButton(
                text = stringResource(R.string.crash_clear),
                onClick = {
                    scope.launch {
                        if (withContext(Dispatchers.IO) { store.clear() }) report = null
                    }
                },
            )
        }
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
    sources: List<SavedSource>,
    importState: ImportState,
    onSave: (url: String, userAgent: String, id: Long?) -> Unit,
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
    var invalid by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    val agentFocus = remember { FocusRequester() }
    val saveFocus = remember { FocusRequester() }
    LaunchedEffect(editing, deleting) {
        if (deleting == null) firstFocus.requestFocusWhenReady()
    }
    BackHandler(enabled = editing) { editing = false }

    fun openForm(existing: SourceEntity?) {
        editingId = existing?.id
        url = existing?.url.orEmpty()
        agent = existing?.userAgent ?: LibraryRepository.DEFAULT_USER_AGENT
        invalid = false
        editing = true
    }

    deleting?.let { entry ->
        val confirmFocus = remember { FocusRequester() }
        Dialog(onDismissRequest = { deleting = null }) {
            LaunchedEffect(Unit) { confirmFocus.requestFocusWhenReady() }
            SettingsCard {
                FLText(
                    text = stringResource(R.string.source_delete_confirm, entry.name, entry.id),
                    style = typography.headline,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.source_delete),
                        onClick = { onDelete(entry.id); deleting = null },
                        modifier = Modifier.focusRequester(confirmFocus),
                    )
                    SecondaryButton(
                        text = stringResource(R.string.source_cancel),
                        onClick = { deleting = null },
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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.source_save),
                    onClick = {
                        val trimmed = url.trim()
                        val uri = Uri.parse(trimmed)
                        if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()) {
                            onSave(trimmed, agent.trim().ifEmpty { LibraryRepository.DEFAULT_USER_AGENT }, editingId)
                            editing = false
                        } else {
                            invalid = true
                        }
                    },
                    modifier = Modifier.focusRequester(saveFocus).focusProperties { up = agentFocus },
                )
                SecondaryButton(
                    text = stringResource(R.string.source_cancel),
                    onClick = { editing = false },
                    modifier = Modifier.focusProperties { up = agentFocus },
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
                            onClick = { onActivate(entry.id) },
                            modifier = Modifier.weight(1f),
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
                    modifier = Modifier.focusRequester(firstFocus),
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
