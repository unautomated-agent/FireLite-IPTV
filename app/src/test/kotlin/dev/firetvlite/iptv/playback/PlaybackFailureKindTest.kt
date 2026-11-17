package dev.firetvlite.iptv.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackFailureKindTest {
    private fun kind(code: String, http: Int? = null) = PlaybackFailureKind.of(code, http)

    @Test fun httpStatusDecidesBeforeTheErrorCode() {
        assertEquals(PlaybackFailureKind.NotFound, kind("ERROR_CODE_IO_BAD_HTTP_STATUS", 404))
        assertEquals(PlaybackFailureKind.Denied, kind("ERROR_CODE_IO_BAD_HTTP_STATUS", 403))
        assertEquals(PlaybackFailureKind.Denied, kind("ERROR_CODE_IO_BAD_HTTP_STATUS", 401))
        assertEquals(PlaybackFailureKind.NotFound, kind("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", 410))
    }

    @Test fun networkCodesWithoutHttpStatusMeanNoConnection() {
        assertEquals(PlaybackFailureKind.NoConnection, kind("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
        assertEquals(PlaybackFailureKind.NoConnection, kind("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))
    }

    @Test fun parsingAndDecoderCodesMeanUnsupported() {
        assertEquals(PlaybackFailureKind.Unsupported, kind("ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED"))
        assertEquals(PlaybackFailureKind.Unsupported, kind("ERROR_CODE_DECODER_INIT_FAILED"))
        assertEquals(PlaybackFailureKind.Unsupported, kind("ERROR_CODE_DECODING_FAILED"))
    }

    @Test fun anythingElseKeepsTheGenericMessage() {
        assertEquals(PlaybackFailureKind.Other, kind("ERROR_CODE_UNSPECIFIED"))
        assertEquals(PlaybackFailureKind.Other, kind("ERROR_CODE_IO_UNSPECIFIED"))
    }
}
