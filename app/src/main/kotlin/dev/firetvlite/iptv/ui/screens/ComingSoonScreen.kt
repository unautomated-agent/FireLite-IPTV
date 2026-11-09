package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.NavIcon
import dev.firetvlite.iptv.ui.navigation.Destination
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

/** Placeholder for destinations that are built in later packages. */
@Composable
fun ComingSoonScreen(
    destination: Destination,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavIcon(
                kind = destination.icon,
                tint = FireLiteTheme.colors.textSecondary,
                modifier = Modifier.size(56.dp),
            )
            FLText(
                text = stringResource(destination.labelRes),
                style = FireLiteTheme.typography.display,
            )
            FLText(
                text = stringResource(R.string.coming_soon_text),
                style = FireLiteTheme.typography.body,
            )
        }
    }
}
