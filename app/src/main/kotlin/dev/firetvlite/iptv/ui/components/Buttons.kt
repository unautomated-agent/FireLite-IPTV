package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import dev.firetvlite.iptv.R
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

/** Setting that is either on or off. OK flips it; the state is shown on the right. */
@Composable
fun ToggleRow(
    text: String,
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stringResource(if (checked) R.string.toggle_on else R.string.toggle_off)
    FocusSurface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth().semantics { role = Role.Switch; stateDescription = state },
        background = SolidColor(FireLiteTheme.colors.surfaceHigh),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FLText(text = text, style = FireLiteTheme.typography.title, maxLines = 2, modifier = Modifier.weight(1f))
            FLText(
                text = state,
                style = FireLiteTheme.typography.title,
                color = if (checked) FireLiteTheme.colors.accent else FireLiteTheme.colors.textSecondary,
                maxLines = 1,
            )
        }
    }
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
