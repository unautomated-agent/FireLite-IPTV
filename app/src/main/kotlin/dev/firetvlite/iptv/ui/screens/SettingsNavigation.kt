@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import kotlinx.coroutines.launch

internal enum class SettingsSection(val title: Int) {
    Sources(R.string.settings_sources), Appearance(R.string.settings_appearance),
    Omdb(R.string.settings_omdb), Health(R.string.health_title),
    Diagnostics(R.string.diagnostics_title), Updates(R.string.settings_updates);

    companion object {
        fun restored(name: String) = entries.firstOrNull { it.name == name } ?: Sources
    }
}

internal class SettingsPaneFocus(val last: () -> String?, val changed: (String) -> Unit) {
    val controls = linkedMapOf<String, FocusRequester>()
    fun target(): FocusRequester? = controls[last()]
}

internal val LocalSettingsPaneFocus = staticCompositionLocalOf<SettingsPaneFocus?> { null }

@Composable
internal fun Modifier.settingsControl(id: String): Modifier {
    val pane = LocalSettingsPaneFocus.current ?: return this
    val focus = remember(pane, id) { FocusRequester() }
    val bounds = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val top = with(LocalDensity.current) { 12.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    DisposableEffect(pane, id) {
        pane.controls[id] = focus
        onDispose { pane.controls.remove(id) }
    }
    return focusRequester(focus).bringIntoViewRequester(bounds).onSizeChanged { size = it }
        .onFocusChanged { state ->
            if (state.hasFocus) {
                pane.changed(id)
                scope.launch {
                    withFrameNanos { }
                    bounds.bringIntoView(Rect(0f, -top, size.width.toFloat(), size.height.toFloat() + top))
                }
            }
        }
}

internal fun reportScrollTarget(position: Int, maximum: Int, down: Boolean, step: Int): Int? =
    if (down && position >= maximum) null
    else (position + if (down) step else -step).coerceIn(0, maximum)

internal fun settingsLastError(report: String): String? =
    listOf("Ostatni błąd odtwarzania:", "Ostatni raport błędu:").firstNotNullOfOrNull { heading ->
        val lines = report.substringAfter("$heading\n", "").substringBefore("\n---\n")
            .lineSequence().filter { it.isNotBlank() }
        val exception = if (heading == "Ostatni raport błędu:")
            lines.firstOrNull { it.startsWith("Uncaught crash:") } else null
        (exception ?: lines.firstOrNull())?.takeUnless { it == "Brak" }
    }
