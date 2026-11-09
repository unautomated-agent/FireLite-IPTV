package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/** Main call to action on a screen. Filled with the accent colour. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonSurface(
        text = text,
        onClick = onClick,
        container = FireLiteTheme.colors.accent,
        content = FireLiteTheme.colors.onAccent,
        modifier = modifier,
    )
}

/** Secondary action next to a [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonSurface(
        text = text,
        onClick = onClick,
        container = FireLiteTheme.colors.surfaceHigh,
        content = FireLiteTheme.colors.textPrimary,
        modifier = modifier,
    )
}

@Composable
private fun ButtonSurface(
    text: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    modifier: Modifier,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        background = SolidColor(container),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            FLText(
                text = text,
                style = FireLiteTheme.typography.title,
                color = content,
                maxLines = 1,
            )
        }
    }
}
