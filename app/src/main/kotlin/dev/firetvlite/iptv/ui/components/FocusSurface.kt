package dev.firetvlite.iptv.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/**
 * The basic focusable element of the TV UI: cards, buttons and list rows are built on it.
 *
 * Focus is shown with a smooth scale-up and a white ring, the two cues that read best from a sofa.
 * The scale is applied in a graphics layer, so animating it costs no re-layout; that keeps it
 * smooth on the Fire TV Stick's modest CPU.
 *
 * @param onClick invoked by the OK button.
 * @param background fill of the surface. It is applied inside the scaling layer so it scales too.
 * @param onFocusChanged reports focus gains and losses, e.g. to preview the focused item.
 * @param content receives the current focus state.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = FireLiteTheme.shapes.medium,
    background: Brush = SolidColor(FireLiteTheme.colors.surface),
    focusedScale: Float = 1.06f,
    onFocusChanged: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "focusScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "focusRing",
    )
    LaunchedEffect(focused) { onFocusChanged(focused) }

    Box(
        modifier = modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .border(
                width = 2.dp,
                color = FireLiteTheme.colors.focusRing.copy(alpha = ringAlpha),
                shape = shape,
            ),
    ) {
        content(focused)
    }
}
