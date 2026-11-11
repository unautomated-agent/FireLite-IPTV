package dev.firetvlite.iptv.playback

import androidx.media3.common.PlaybackException
import java.net.URI

internal enum class RecoveryAction { LiveEdge, Progressive, RestoreHls, Retry }

internal class PlaybackRecovery {
    private enum class SourceMode { Original, Progressive }

    private var mode = SourceMode.Original
    private var successfulMode: SourceMode? = null
    private var manifestLoaded = false
    private var fallbackUsed = false

    fun onManifestLoaded() {
        if (mode == SourceMode.Original) manifestLoaded = true
    }

    fun onReady() {
        successfulMode = mode
    }

    fun onRecovery(action: RecoveryAction) {
        when (action) {
            RecoveryAction.Progressive -> {
                fallbackUsed = true
                mode = SourceMode.Progressive
            }
            RecoveryAction.RestoreHls -> mode = SourceMode.Original
            else -> Unit
        }
    }

    fun action(code: Int, url: String, cause: Throwable? = null): RecoveryAction {
        if (mode == SourceMode.Progressive) return if (successfulMode == SourceMode.Progressive)
            RecoveryAction.Retry else RecoveryAction.RestoreHls
        if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return RecoveryAction.LiveEdge
        val hls = runCatching { URI(url).path.lowercase().endsWith(".m3u8") }.getOrDefault(false)
        val causes = HashSet<Throwable>()
        var current = cause
        var unrecognized = false
        while (current != null && causes.add(current) && causes.size <= 12) {
            if (current.javaClass.simpleName == "UnrecognizedInputFormatException") unrecognized = true
            current = current.cause
        }
        val parsing = code in setOf(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED)
        return if (hls && !manifestLoaded && successfulMode == null && !fallbackUsed && (parsing || unrecognized))
            RecoveryAction.Progressive else RecoveryAction.Retry
    }
}
