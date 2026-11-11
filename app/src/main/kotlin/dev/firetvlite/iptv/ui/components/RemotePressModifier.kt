package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.firetvlite.iptv.playback.RemotePress
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun Modifier.remotePress(keys: Set<Key>, onShort: () -> Unit, onLong: () -> Unit): Modifier {
    val press = remember { RemotePress() }
    val scope = rememberCoroutineScope()
    val shortAction by rememberUpdatedState(onShort)
    val longAction by rememberUpdatedState(onLong)
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    return onPreviewKeyEvent { event ->
        if (event.key !in keys) false else {
            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                job?.cancel()
                press.begin()
                job = scope.launch { delay(600); if (press.longPress()) longAction() }
            } else if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.isLongPress) {
                job?.cancel()
                if (press.longPress()) longAction()
            } else if (event.type == KeyEventType.KeyUp) {
                job?.cancel()
                if (press.release()) shortAction()
            }
            true
        }
    }
}
