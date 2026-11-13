package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.playlist.PlaylistHttpHeaders
import dev.firetvlite.iptv.playback.StreamRequest
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import kotlinx.coroutines.flow.flowOf

@Composable
internal fun ChannelHealthSection(source: SourceEntity?) {
    val container = (LocalContext.current.applicationContext as FireLiteApplication).container
    val playback = container.playback
    val progress by playback.healthCheck.collectAsState()
    val dao = container.database.channelDao()
    var group by remember(source?.id) { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    val all = stringResource(R.string.health_all)
    val groups by remember(source?.id) { source?.let { dao.observeGroups(it.id) } ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val count by remember(source?.id) { source?.let { dao.observeCount(it.id) } ?: flowOf(0) }.collectAsState(0)
    val selected = group?.takeIf { name -> groups.any { it.groupName == name } }
    val selectedCount = selected?.let { name -> groups.first { it.groupName == name }.channelCount } ?: count
    val selectedName = selected?.ifBlank { stringResource(R.string.health_ungrouped) } ?: all
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FLText(stringResource(R.string.health_title), style = FireLiteTheme.typography.title)
        if (source != null && !progress.running) {
            SecondaryButton("$selectedName ($selectedCount)", { choosing = true })
            if (choosing) Dialog(onDismissRequest = { choosing = false }) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocusWhenReady() }
                Column(Modifier.background(FireLiteTheme.colors.surfaceHigh).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("$all ($count)", { group = null; choosing = false }, Modifier.focusRequester(focus))
                    groups.forEach { item ->
                        SecondaryButton("${item.groupName.ifBlank { stringResource(R.string.health_ungrouped) }} (${item.channelCount})",
                            { group = item.groupName; choosing = false })
                    }
                }
            }
        }
        if (source != null || progress.running) PrimaryButton(
            stringResource(if (progress.running) R.string.health_stop else R.string.health_start), {
                if (progress.running) playback.stopHealthCheck()
                else if (source != null) playback.startHealthCheck(selectedName, selectedCount) { index ->
                    val channel = if (selected == null) dao.allAt(source.id, index) else dao.groupAt(source.id, selected, index)
                    channel?.let { StreamRequest(it.streamUrl, source.userAgent, channelName = it.name,
                        headers = PlaylistHttpHeaders.decode(it.requestHeaders), userInitiated = false) }
                }
            })
        if (progress.running || progress.total > 0) {
            FLText(progress.scope, style = FireLiteTheme.typography.label)
            FLText(stringResource(R.string.health_progress, progress.checked, progress.total, progress.dead, progress.healthy),
                style = FireLiteTheme.typography.label)
            ProgressBar(if (progress.total > 0) progress.checked.toFloat() / progress.total else 0f)
            if (progress.current.isNotBlank()) FLText(progress.current, style = FireLiteTheme.typography.label)
            if (!progress.running) FLText(stringResource(when {
                progress.error -> R.string.health_error
                progress.complete -> R.string.health_complete
                else -> R.string.health_stopped
            }), style = FireLiteTheme.typography.label)
        }
    }
}
