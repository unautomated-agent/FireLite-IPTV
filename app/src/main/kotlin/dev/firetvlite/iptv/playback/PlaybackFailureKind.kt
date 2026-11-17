package dev.firetvlite.iptv.playback

/** What the viewer is told when a channel fails; the raw code stays visible in brackets. */
enum class PlaybackFailureKind {
    NotFound, Denied, NoConnection, Unsupported, Other;

    companion object {
        fun of(code: String, httpStatus: Int?): PlaybackFailureKind = when {
            httpStatus == 401 || httpStatus == 403 -> Denied
            httpStatus != null -> NotFound
            code == "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED" || code == "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT" -> NoConnection
            code.startsWith("ERROR_CODE_PARSING_") || code.startsWith("ERROR_CODE_DECOD") -> Unsupported
            else -> Other
        }
    }
}
