package dev.firetvlite.iptv.ui.screens

internal data class ChannelFocusTarget(val url: String, val index: Int) {
    fun index(resolvedIndex: Int?, count: Int): Int? =
        if (count <= 0) null else (resolvedIndex ?: index).coerceIn(0, count - 1)
}
