package dev.firetvlite.iptv.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.NavIcon
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/** Width of the rail while it only shows icons. Screens reserve this much space at their start. */
val RailCollapsedWidth: Dp = 64.dp

private val RailExpandedWidth: Dp = 208.dp

/**
 * Left navigation rail. It shows icons only and grows to show labels while it holds focus, drawing
 * over the content instead of pushing it, so the content never jumps.
 *
 * Choosing an entry needs OK, so moving over the rail is cheap and never loads a screen by accident.
 *
 * @param focusRequesters one requester per entry, so the shell can send focus back to the rail.
 * @param onFocusWithinChanged true while any entry of the rail has focus.
 */
@Composable
fun AppNavigationRail(
    selected: Destination,
    focusRequesters: Map<Destination, FocusRequester>,
    onSelect: (Destination) -> Unit,
    onFocusWithinChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (hasFocus) RailExpandedWidth else RailCollapsedWidth,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "railWidth",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (hasFocus) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "railLabelAlpha",
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .background(FireLiteTheme.colors.rail)
            .onFocusChanged { state ->
                hasFocus = state.hasFocus
                onFocusWithinChanged(state.hasFocus)
            }
            .focusGroup()
            .padding(horizontal = 10.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Destination.entries.forEach { destination ->
            RailItem(
                destination = destination,
                selected = destination == selected,
                labelAlpha = labelAlpha,
                focusRequester = focusRequesters.getValue(destination),
                onClick = { onSelect(destination) },
            )
        }
    }
}

@Composable
private fun RailItem(
    destination: Destination,
    selected: Boolean,
    labelAlpha: Float,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
) {
    val colors = FireLiteTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val container by animateColorAsState(
        targetValue = if (focused) colors.accent.copy(alpha = 0.20f) else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label = "railItemContainer",
    )
    val tint by animateColorAsState(
        targetValue = when {
            focused -> colors.textPrimary
            selected -> colors.accent
            else -> colors.textSecondary
        },
        animationSpec = tween(durationMillis = 150),
        label = "railItemTint",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(FireLiteTheme.shapes.medium)
            .background(container)
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavIcon(kind = destination.icon, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(14.dp))
        FLText(
            text = stringResource(destination.labelRes),
            style = FireLiteTheme.typography.title,
            color = tint,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.graphicsLayer { alpha = labelAlpha },
        )
    }
}
