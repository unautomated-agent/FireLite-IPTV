package dev.firetvlite.iptv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colour tokens. Only a dark palette exists: a TV is watched in a dim room. */
@Immutable
class FireLiteColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val rail: Color,
    val accent: Color,
    val onAccent: Color,
    val focusRing: Color,
    val textPrimary: Color,
    val textSecondary: Color,
)

/** Text styles, sized for the 960 dp reference canvas (see [ReferenceDensity]). */
@Immutable
class FireLiteTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
)

@Immutable
class FireLiteShapes(
    val small: RoundedCornerShape,
    val medium: RoundedCornerShape,
    val large: RoundedCornerShape,
)

private val DarkColors = FireLiteColors(
    background = Color(0xFF0B0F14),
    surface = Color(0xFF121922),
    surfaceHigh = Color(0xFF1A2430),
    rail = Color(0xFF0E141B),
    accent = Color(0xFF4FC3F7),
    onAccent = Color(0xFF06222E),
    focusRing = Color(0xF2FFFFFF),
    textPrimary = Color(0xFFF2F5F8),
    textSecondary = Color(0xFF9AA7B4),
)

private val DefaultTypography = FireLiteTypography(
    display = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.4).sp,
        color = DarkColors.textPrimary,
    ),
    headline = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
        color = DarkColors.textPrimary,
    ),
    title = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        color = DarkColors.textPrimary,
    ),
    body = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        color = DarkColors.textSecondary,
    ),
    label = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
        color = DarkColors.textSecondary,
    ),
)

private val DefaultShapes = FireLiteShapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
)

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalTypography = staticCompositionLocalOf { DefaultTypography }
private val LocalShapes = staticCompositionLocalOf { DefaultShapes }

/** Accessor for the current theme tokens, e.g. `FireLiteTheme.colors.accent`. */
object FireLiteTheme {
    val colors: FireLiteColors
        @Composable @ReadOnlyComposable get() = LocalColors.current

    val typography: FireLiteTypography
        @Composable @ReadOnlyComposable get() = LocalTypography.current

    val shapes: FireLiteShapes
        @Composable @ReadOnlyComposable get() = LocalShapes.current
}

@Composable
fun FireLiteTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalColors provides DarkColors,
        LocalTypography provides DefaultTypography,
        LocalShapes provides DefaultShapes,
        content = content,
    )
}
