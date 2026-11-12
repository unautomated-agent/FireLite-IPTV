package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

@Composable
fun ArchiveIcon(modifier: Modifier = Modifier, description: String = stringResource(R.string.guide_archive_available)) {
    val color = FireLiteTheme.colors.accent
    Canvas(modifier.size(16.dp).semantics { contentDescription = description }) {
        val unit = size.minDimension / 24f
        val stroke = 2 * unit
        drawArc(color, -180f, 315f, false, Offset(4 * unit, 4 * unit), Size(16 * unit, 16 * unit), style = Stroke(stroke))
        drawPath(Path().apply {
            moveTo(unit, 8 * unit); lineTo(4 * unit, 12 * unit); lineTo(8 * unit, 9 * unit)
        }, color, style = Stroke(stroke))
        drawLine(color, Offset(12 * unit, 7 * unit), Offset(12 * unit, 12 * unit), stroke)
        drawLine(color, Offset(12 * unit, 12 * unit), Offset(16 * unit, 14 * unit), stroke)
    }
}
