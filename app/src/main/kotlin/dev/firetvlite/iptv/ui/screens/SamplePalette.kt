package dev.firetvlite.iptv.ui.screens

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Gradient pairs that stand in for posters and channel logos while no real artwork exists. */
private val palettes = listOf(
    listOf(Color(0xFF3B5BDB), Color(0xFF15192E)),
    listOf(Color(0xFF0CA678), Color(0xFF0B2420)),
    listOf(Color(0xFFD9480F), Color(0xFF2A130A)),
    listOf(Color(0xFF9C36B5), Color(0xFF1F0F27)),
    listOf(Color(0xFF1098AD), Color(0xFF0A2127)),
    listOf(Color(0xFFC2255C), Color(0xFF2A0D18)),
)

internal fun sampleBrush(index: Int): Brush =
    Brush.verticalGradient(palettes[index % palettes.size])
