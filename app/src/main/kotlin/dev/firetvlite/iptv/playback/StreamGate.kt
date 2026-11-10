package dev.firetvlite.iptv.playback

/**
 * Guards the one hard rule of the app: the provider allows a single connection, so the same stream
 * must never be opened twice and no second stream may exist next to the first.
 *
 * The gate only remembers which URL is (or is about to be) connected. Every request to play goes through
 * [onRequest]; a request for the URL that is already active is dropped.
 */
class StreamGate {

    enum class Action {
        /** Close the current stream (if any) and open the requested one. */
        Switch,

        /** The requested stream is already active; do nothing. */
        Ignore,
    }

    /** The URL that is connected or being connected, or null when nothing plays. */
    var activeUrl: String? = null
        private set

    fun onRequest(url: String): Action {
        if (url == activeUrl) return Action.Ignore
        activeUrl = url
        return Action.Switch
    }

    /** Call when the stream was closed on purpose or has failed for good. */
    fun onStopped() {
        activeUrl = null
    }
}
