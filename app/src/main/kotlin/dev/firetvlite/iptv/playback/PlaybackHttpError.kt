package dev.firetvlite.iptv.playback

import androidx.media3.datasource.HttpDataSource

internal object PlaybackHttpError {
    fun requestUri(error: Throwable?): String? {
        val seen = hashSetOf<Throwable>()
        var cause = error
        while (cause != null && seen.add(cause) && seen.size <= 12) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.dataSpec?.uri?.toString()
            cause = cause.cause
        }
        return null
    }

    fun status(error: Throwable?): Int? {
        val seen = hashSetOf<Throwable>()
        var cause = error
        while (cause != null && seen.add(cause) && seen.size <= 12) {
            if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
            cause = cause.cause
        }
        return null
    }

    fun displayedStatus(code: String, error: Throwable?, recordedStatus: Int? = null): Int? =
        (status(error) ?: recordedStatus?.takeIf { code.startsWith("ERROR_CODE_IO_") })
            ?.takeIf { it in setOf(403, 404, 410) }

    fun message(code: String, error: Throwable?, recordedStatus: Int? = null): String =
        displayedStatus(code, error, recordedStatus)?.let { "Kanał niedostępny u dostawcy (HTTP $it)" } ?: code
}
