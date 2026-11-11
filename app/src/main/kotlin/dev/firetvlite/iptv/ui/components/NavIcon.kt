package dev.firetvlite.iptv.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** The icons of the navigation rail. They are drawn in code on a 24 x 24 grid, so no assets are needed. */
enum class NavIconKind { Home, Live, Guide, Search, Favorites, Settings }

@Composable
fun NavIcon(
    kind: NavIconKind,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val s = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun at(x: Float, y: Float) = Offset(x * s, y * s)

        when (kind) {
            NavIconKind.Home -> {
                val roof = Path().apply {
                    moveTo(3f * s, 11f * s)
                    lineTo(12f * s, 3.5f * s)
                    lineTo(21f * s, 11f * s)
                }
                val walls = Path().apply {
                    moveTo(5.5f * s, 9.5f * s)
                    lineTo(5.5f * s, 20f * s)
                    lineTo(10f * s, 20f * s)
                    lineTo(10f * s, 14f * s)
                    lineTo(14f * s, 14f * s)
                    lineTo(14f * s, 20f * s)
                    lineTo(18.5f * s, 20f * s)
                    lineTo(18.5f * s, 9.5f * s)
                }
                drawPath(roof, tint, style = stroke)
                drawPath(walls, tint, style = stroke)
            }

            NavIconKind.Live -> {
                drawRoundRect(
                    color = tint,
                    topLeft = at(3f, 4.5f),
                    size = Size(18f * s, 12f * s),
                    cornerRadius = CornerRadius(2.5f * s),
                    style = stroke,
                )
                drawLine(tint, at(8f, 20.5f), at(16f, 20.5f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                val play = Path().apply {
                    moveTo(10.2f * s, 7.6f * s)
                    lineTo(10.2f * s, 13.4f * s)
                    lineTo(14.8f * s, 10.5f * s)
                    close()
                }
                drawPath(play, tint)
            }

            NavIconKind.Guide -> {
                drawRoundRect(
                    color = tint,
                    topLeft = at(3.5f, 4.5f),
                    size = Size(17f * s, 15.5f * s),
                    cornerRadius = CornerRadius(2.5f * s),
                    style = stroke,
                )
                drawLine(tint, at(3.5f, 9.5f), at(20.5f, 9.5f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(8.5f, 9.5f), at(8.5f, 20f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(8f, 2.5f), at(8f, 6f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(16f, 2.5f), at(16f, 6f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
            }

            NavIconKind.Search -> {
                drawCircle(tint, radius = 6.2f * s, center = at(10.5f, 10.5f), style = stroke)
                drawLine(tint, at(15.2f, 15.2f), at(20.5f, 20.5f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
            }

            NavIconKind.Favorites -> {
                val heart = Path().apply {
                    moveTo(12f * s, 20.5f * s)
                    cubicTo(12f * s, 20.5f * s, 3f * s, 14.5f * s, 3f * s, 8.6f * s)
                    cubicTo(3f * s, 5.6f * s, 5.2f * s, 3.6f * s, 7.7f * s, 3.6f * s)
                    cubicTo(9.5f * s, 3.6f * s, 11.1f * s, 4.6f * s, 12f * s, 6.2f * s)
                    cubicTo(12.9f * s, 4.6f * s, 14.5f * s, 3.6f * s, 16.3f * s, 3.6f * s)
                    cubicTo(18.8f * s, 3.6f * s, 21f * s, 5.6f * s, 21f * s, 8.6f * s)
                    cubicTo(21f * s, 14.5f * s, 12f * s, 20.5f * s, 12f * s, 20.5f * s)
                    close()
                }
                drawPath(heart, tint, style = stroke)
            }

            NavIconKind.Settings -> {
                // Three sliders: a line with a ring on it.
                drawLine(tint, at(3f, 6f), at(5.8f, 6f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(10.2f, 6f), at(21f, 6f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 2.2f * s, center = at(8f, 6f), style = stroke)

                drawLine(tint, at(3f, 12f), at(13.8f, 12f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(18.2f, 12f), at(21f, 12f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 2.2f * s, center = at(16f, 12f), style = stroke)

                drawLine(tint, at(3f, 18f), at(6.8f, 18f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawLine(tint, at(11.2f, 18f), at(21f, 18f), strokeWidth = 1.9f * s, cap = StrokeCap.Round)
                drawCircle(tint, radius = 2.2f * s, center = at(9f, 18f), style = stroke)
            }
        }
    }
}
