package dev.firetvlite.iptv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Width of the design canvas in dp. A 1080p TV at xhdpi is exactly 960 x 540 dp. */
const val REFERENCE_WIDTH_DP = 960f

/** Pixels per dp that make a screen [widthPx] pixels wide exactly [referenceWidthDp] dp wide. */
fun referenceDensity(widthPx: Int, referenceWidthDp: Float = REFERENCE_WIDTH_DP): Float =
    widthPx / referenceWidthDp

/**
 * Normalises the layout to a 960 dp wide canvas, whatever density the TV reports.
 * 1 dp is then always 1/960 of the screen width, so the UI looks identical on every device.
 */
@Composable
fun ReferenceDensity(content: @Composable () -> Unit) {
    val widthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val density = remember(widthPx) { Density(density = referenceDensity(widthPx), fontScale = 1f) }
    CompositionLocalProvider(LocalDensity provides density, content = content)
}
