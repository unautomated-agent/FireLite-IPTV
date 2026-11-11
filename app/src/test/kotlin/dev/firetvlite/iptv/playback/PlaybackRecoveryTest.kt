package dev.firetvlite.iptv.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun behindLiveWindowAlwaysSeeksWithinTheCurrentSession() {
        assertEquals(RecoveryAction.LiveEdge, PlaybackRecovery.action(
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, "https://provider/live.m3u8", true))
    }

    @Test fun onlyOneProgressiveFallbackForAnHlsParseError() {
        for (code in listOf(3001, 3002, 3003, 3004)) {
            assertEquals(RecoveryAction.Progressive, PlaybackRecovery.action(code, "https://provider/live.M3U8?token=masked", false))
            assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(code, "https://provider/live.m3u8", true))
        }
    }

    @Test fun nestedUnrecognizedInputAlsoTriggersFallback() {
        assertEquals(RecoveryAction.Progressive, PlaybackRecovery.action(2000, "https://provider/live.m3u8", false,
            Exception("outer", UnrecognizedInputFormatException())))
    }

    @Test fun anEstablishedHlsManifestOrReadySessionNeverFallsBackForSegmentErrors() {
        for (code in listOf(3001, 3002, 3003, 3004)) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(code, "https://provider/live.m3u8", false,
                hlsEstablished = true))
        }
        assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(2000, "https://provider/live.m3u8", false,
            UnrecognizedInputFormatException(), hlsEstablished = true))
    }

    @Test fun anyProgressiveFailureRestoresHlsAndTheFallbackBudgetStaysUsed() {
        for (code in listOf(1002, 2001, 3001, 3002, 4001)) {
            assertEquals(RecoveryAction.RestoreHls, PlaybackRecovery.action(code, "https://provider/live.m3u8", true,
                progressiveActive = true))
        }
        assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(3002, "https://provider/live.m3u8", true))
    }

    @Test fun networkCodecNonHlsAndInvalidAddressesDoNotChangeTheSourceType() {
        for (url in listOf("", "not a URL", "https://provider/live.ts", "https://provider/live.mp4")) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(3002, url, false))
        }
        for (code in listOf(2001, 2002, 4001, 4003, 5001)) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery.action(code, "https://provider/live.m3u8", false))
        }
    }

    private class UnrecognizedInputFormatException : Exception()
}
