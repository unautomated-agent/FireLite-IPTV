package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.DeadChannels

@Composable
internal fun DeadChannelIcon(streamUrl: String, modifier: Modifier = Modifier) {
    val registry = (LocalContext.current.applicationContext as FireLiteApplication).container.deadChannels
    val state by registry.state.collectAsState()
    val key = remember(streamUrl) { DeadChannels.key(streamUrl) }
    if (key in state.keys) {
        val description = stringResource(R.string.channel_dead)
        Canvas(modifier.size(16.dp).semantics { contentDescription = description }) {
            val inset = size.minDimension * 0.22f
            val color = Color(0xFFFF7A7A)
            val stroke = size.minDimension * 0.17f
            drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), stroke, StrokeCap.Round)
        }
    }
}
