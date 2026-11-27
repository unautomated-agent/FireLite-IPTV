package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R

/** Red dot in a ring: the programme on air right now. */
@Composable
fun LiveIcon(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.channel_timeline_live)
    Canvas(modifier.size(16.dp).semantics { contentDescription = description }) {
        val color = Color(0xFFFF4D4D)
        drawCircle(color, size.minDimension * 0.24f)
        drawCircle(color, size.minDimension * 0.42f, style = Stroke(size.minDimension * 0.1f))
    }
}
