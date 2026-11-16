package dev.firetvlite.iptv.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.firetvlite.iptv.CrashReportStore
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.film.omdbKeyStatus
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.epg.EpgState
import dev.firetvlite.iptv.data.library.DiagnosticsReport
import dev.firetvlite.iptv.data.library.SourceDiagnostics
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.components.minuteClock
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import dev.firetvlite.iptv.playback.PlaybackDiagnosticsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsSection(source: SourceEntity?, state: EpgState?, diagnostics: Flow<SourceDiagnostics>) {
    val stats by diagnostics.collectAsState(SourceDiagnostics())
    val context = LocalContext.current
    val channelHealth by (context.applicationContext as FireLiteApplication).container.deadChannels.state.collectAsState()
    val reporting = (context.applicationContext as FireLiteApplication).container.errorReports
    val reportingStatus by reporting.state.collectAsState()
    val stalls by (context.applicationContext as FireLiteApplication).container.stalls.state.collectAsState()
    val store = remember(context) { CrashReportStore(File(context.filesDir, "last-crash.txt")) }
    var crash by remember { mutableStateOf<String?>(null) }
    var playback by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val startup by (context.applicationContext as FireLiteApplication).container.startup.latest.collectAsState()
    LaunchedEffect(store) {
        withContext(Dispatchers.IO) { store.read() to PlaybackDiagnosticsStore(context.filesDir).summary() }.let {
            crash = it.first
            playback = it.second
        }
    }
    val omdbKey by (context.applicationContext as FireLiteApplication).container.films.preferences.key.collectAsState()
    val omdbStats by (context.applicationContext as FireLiteApplication).container.films.preferences.stats.collectAsState()
    val now by minuteClock()
    val today = omdbStats.today(now)
    val baseReport = remember(source, state, stats, crash, playback, omdbKey.isNotBlank(), today) {
        context.getString(R.string.omdb_key_diagnostics, context.getString(omdbKeyStatus(omdbKey.isNotBlank()))) +
            "\n" + context.getString(R.string.omdb_stats_diagnostics, today.requests, today.hits, today.misses,
                if (omdbKey.isBlank()) "NO_KEY" else today.lastError) +
            "\n" + today.missSummary() + "\n" + DiagnosticsReport.text(source, state, stats, crash, playback)
    }
    val report = remember(baseReport, channelHealth, stalls) {
        channelHealth.summary() + "\n" + stalls.joinToString("\n") { it.line() } + "\n" + baseReport
    }
    val qrReport = remember(baseReport, channelHealth, stalls) {
        channelHealth.qrSummary() + "\n" +
            stalls.joinToString("\n") { it.line(compact = true) } + "\n" + baseReport
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FLText(stringResource(R.string.startup_diagnostics, startup.total?.toString() ?: "—", startup.detail()),
            style = FireLiteTheme.typography.body)
        val lastError = settingsLastError(report)
        FLText(stringResource(R.string.settings_last_error, lastError ?: stringResource(R.string.settings_no_report)),
            style = FireLiteTheme.typography.body, maxLines = 1)
        FLText(stringResource(R.string.settings_dead_count, channelHealth.entries.size), style = FireLiteTheme.typography.body)
        FLText(stringResource(R.string.settings_stall_count, stalls.size, settingsWatchdogStallCount(crash)),
            style = FireLiteTheme.typography.body)
        FLText(stringResource(R.string.settings_health_summary, channelHealth.check.ifBlank { stringResource(R.string.settings_no_report) }),
            style = FireLiteTheme.typography.body, maxLines = 2)
        PrimaryButton(stringResource(R.string.diagnostics_open), { opened = true }, Modifier.settingsControl("diagnostics-open"))
        SecondaryButton(stringResource(R.string.reports_automatic,
            stringResource(if (reportingStatus.enabled) R.string.reports_on else R.string.reports_off)),
            { reporting.setEnabled(!reportingStatus.enabled) }, Modifier.settingsControl("reports-toggle"))
        val lastSent = if (reportingStatus.lastSent == 0L) stringResource(R.string.reports_never) else
            DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(reportingStatus.lastSent))
        FLText(stringResource(R.string.reports_status, reportingStatus.pending, lastSent,
            reportingStatus.lastError.ifBlank { "—" }), style = FireLiteTheme.typography.body)
        if (reportingStatus.blocked) FLText(stringResource(R.string.reports_blocked), style = FireLiteTheme.typography.body)
        PrimaryButton(stringResource(R.string.reports_send_now), reporting::sendNow, Modifier.settingsControl("reports-send"))
    }
    if (opened) {
        val text = DiagnosticsReport.payload(qrReport)
        val bitmap by produceState<Bitmap?>(null, text) {
            value = withContext(Dispatchers.Default) {
                val matrix = DiagnosticsReport.qr(text)
                val pixels = IntArray(matrix.width * matrix.height) { i ->
                    if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            }
        }
        val scroll = rememberScrollState()
        val close = remember { FocusRequester() }
        val reportFocus = remember { FocusRequester() }
        val step = with(LocalDensity.current) { 80.dp.roundToPx() }
        Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LaunchedEffect(Unit) { close.requestFocusWhenReady() }
            Column(Modifier.width(864.dp).height(486.dp).background(FireLiteTheme.colors.surface).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FLText(stringResource(R.string.diagnostics_title), style = FireLiteTheme.typography.headline)
                FLText(stringResource(R.string.diagnostics_scan), style = FireLiteTheme.typography.body)
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FocusSurface(onClick = { scope.launch { scroll.animateScrollTo(0) } }, focusedScale = 1f,
                        modifier = Modifier.weight(1f).focusRequester(reportFocus).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                                val target = reportScrollTarget(scroll.value, scroll.maxValue, event.key == Key.DirectionDown, step)
                                if (target == null) close.requestFocus()
                                else scope.launch { scroll.scrollTo(target) }
                                true
                            } else false
                        }) {
                        FLText(report, style = FireLiteTheme.typography.body,
                            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp))
                    }
                    bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.diagnostics_qr),
                        Modifier.fillMaxHeight().aspectRatio(1f).background(Color.White)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(stringResource(R.string.diagnostics_close), { opened = false },
                        Modifier.focusRequester(close).focusProperties { up = reportFocus })
                    if (crash != null) SecondaryButton(stringResource(R.string.crash_clear), {
                        scope.launch { if (withContext(Dispatchers.IO) { store.clear() }) crash = null }
                    })
                }
            }
        }
    }
}
