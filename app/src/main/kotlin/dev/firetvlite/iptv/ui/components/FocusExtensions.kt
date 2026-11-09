package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Requests focus as soon as the target is attached to the layout.
 *
 * Lazy lists compose their items during measurement, so right after the first composition the
 * [FocusRequester] may not be attached yet and `requestFocus()` would throw. This retries once per
 * frame for a short while instead of crashing or silently losing the initial focus.
 */
suspend fun FocusRequester.requestFocusWhenReady(maxFrames: Int = 20) {
    repeat(maxFrames) {
        withFrameNanos { }
        val focused = try {
            requestFocus()
            true
        } catch (ignored: IllegalStateException) {
            false
        }
        if (focused) return
    }
}
