package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/** Thin progress bar for watch progress or the running time of a programme. [progress] is 0..1. */
@Composable
fun ProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    liveProgress: Float? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x33FFFFFF)),
    ) {
        if (liveProgress != null) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(liveProgress.coerceIn(0f, 1f))
                .background(Color(0x99FFFFFF)))
        }
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(FireLiteTheme.colors.accent),
        )
        if (liveProgress != null) Canvas(Modifier.fillMaxSize()) {
            val x = (size.width * liveProgress.coerceIn(0f, 1f)).coerceIn(1f, (size.width - 1f).coerceAtLeast(1f))
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
    }
}
