package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import kotlinx.coroutines.delay

@Composable
internal fun minuteClock() = produceState(System.currentTimeMillis()) {
    while (true) {
        val now = System.currentTimeMillis()
        value = now
        delay(millisUntilNextMinute(now))
    }
}

@Composable
internal fun WarsawClockBox(modifier: Modifier = Modifier) {
    val now by minuteClock()
    FLText(clockTime(now), style = FireLiteTheme.typography.title,
        color = FireLiteTheme.colors.textPrimary, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp))
}
