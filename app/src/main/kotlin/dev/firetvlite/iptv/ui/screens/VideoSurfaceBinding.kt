package dev.firetvlite.iptv.ui.screens

internal fun <P : Any> rebindVideoSurfaceOnResize(
    player: P?,
    width: Int,
    height: Int,
    oldWidth: Int,
    oldHeight: Int,
    bind: (P?) -> Unit,
) {
    if (player == null || width <= 0 || height <= 0 || oldWidth <= 0 || oldHeight <= 0) return
    if (width == oldWidth && height == oldHeight) return
    bind(null)
    bind(player)
}
