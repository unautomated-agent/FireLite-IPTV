package dev.firetvlite.iptv.playback

import androidx.media3.common.PlaybackException
import java.net.URI

internal enum class RecoveryAction { LiveEdge, Progressive, Retry }

internal object PlaybackRecovery {
    fun action(code: Int, url: String, fallbackUsed: Boolean, cause: Throwable? = null): RecoveryAction {
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
        return if (hls && !fallbackUsed && (parsing || unrecognized)) RecoveryAction.Progressive else RecoveryAction.Retry
    }
}
