package dev.firetvlite.iptv.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.firetvlite.iptv.CrashReportStore
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
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun DiagnosticsSection(source: SourceEntity?, state: EpgState?, diagnostics: Flow<SourceDiagnostics>) {
    val stats by diagnostics.collectAsState(SourceDiagnostics())
    val context = LocalContext.current
    val store = remember(context) { CrashReportStore(File(context.filesDir, "last-crash.txt")) }
    var crash by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(store) { crash = withContext(Dispatchers.IO) { store.read() } }
    val report = remember(source, state, stats, crash) { DiagnosticsReport.text(source, state, stats, crash) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FLText(stringResource(R.string.diagnostics_title), style = FireLiteTheme.typography.headline)
        PrimaryButton(stringResource(R.string.diagnostics_open), { opened = true })
        FLText(DiagnosticsReport.payload(report).substringBefore("Ostatni raport błędu:"), style = FireLiteTheme.typography.label)
    }
    if (opened) {
        val text = DiagnosticsReport.payload(report)
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
        Dialog(onDismissRequest = { opened = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            LaunchedEffect(Unit) { close.requestFocusWhenReady() }
            Column(Modifier.width(1120.dp).background(FireLiteTheme.colors.surface).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FLText(stringResource(R.string.diagnostics_title), style = FireLiteTheme.typography.headline)
                FLText(stringResource(R.string.diagnostics_scan), style = FireLiteTheme.typography.label)
                Row(Modifier.height(390.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FocusSurface(onClick = { scope.launch { scroll.animateScrollTo(0) } }, focusedScale = 1f,
                        modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                                scope.launch { scroll.animateScrollBy(if (event.key == Key.DirectionDown) 100f else -100f) }
                                true
                            } else false
                        }) {
                        FLText(report, style = FireLiteTheme.typography.body,
                            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp))
                    }
                    bitmap?.let { Image(it.asImageBitmap(), stringResource(R.string.diagnostics_qr),
                        Modifier.size(390.dp).background(Color.White)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(stringResource(R.string.diagnostics_close), { opened = false }, Modifier.focusRequester(close))
                    if (crash != null) SecondaryButton(stringResource(R.string.crash_clear), {
                        scope.launch { if (withContext(Dispatchers.IO) { store.clear() }) crash = null }
                    })
                }
            }
        }
    }
}
