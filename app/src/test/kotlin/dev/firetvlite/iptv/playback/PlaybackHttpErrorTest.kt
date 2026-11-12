package dev.firetvlite.iptv.playback

import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.*
import org.junit.Test

internal fun httpFailure(status: Int): HttpDataSource.InvalidResponseCodeException {
    val unsafe = Class.forName("sun.misc.Unsafe")
    val field = unsafe.getDeclaredField("theUnsafe").apply { isAccessible = true }
    val error = unsafe.getMethod("allocateInstance", Class::class.java)
        .invoke(field.get(null), HttpDataSource.InvalidResponseCodeException::class.java)
        as HttpDataSource.InvalidResponseCodeException
    HttpDataSource.InvalidResponseCodeException::class.java.getField("responseCode").apply { isAccessible = true }.setInt(error, status)
    return error
}

class PlaybackHttpErrorTest {
    @Test fun typedHttpStatusSurvivesNestedSourceErrorsAndMapsToPolish() {
        for (status in listOf(403, 404, 410)) {
            val cause = IllegalStateException("Source error", httpFailure(status))
            assertEquals(status, PlaybackHttpError.status(cause))
            assertEquals("Kanał niedostępny u dostawcy (HTTP $status)", PlaybackHttpError.message("NETWORK_FAILED", cause))
        }
    }

    @Test fun latestRecordedHttpFailureIsUsedWhenFinalErrorHasNoHttpCause() {
        assertEquals("Kanał niedostępny u dostawcy (HTTP 404)",
            PlaybackHttpError.message("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", java.io.IOException("network"), 404))
        assertEquals("Kanał niedostępny u dostawcy (HTTP 403)", PlaybackHttpError.message("failed", httpFailure(403), 404))
    }

    @Test fun absentOrUnmappedHttpStatusKeepsOriginalError() {
        assertNull(PlaybackHttpError.status(null))
        assertEquals("decoder", PlaybackHttpError.message("decoder", null))
        assertEquals("failed", PlaybackHttpError.message("failed", httpFailure(500)))
        assertEquals("failed", PlaybackHttpError.message("failed", null, 200))
        assertEquals("ERROR_CODE_DECODING_FAILED", PlaybackHttpError.message("ERROR_CODE_DECODING_FAILED", null, 404))
        assertNull(PlaybackHttpError.displayedStatus("ERROR_CODE_DECODING_FAILED", null, 404))
    }

    @Test fun cyclicCausesAreBounded() {
        val first = Exception("first")
        val second = Exception("second", first)
        first.initCause(second)
        assertNull(PlaybackHttpError.status(first))
    }
}
