package dev.firetvlite.iptv.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun providerHttpErrorsRetryTheOriginalHlsSourceWithoutProgressiveFallback() {
        for (status in listOf(403, 404, 410)) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery().action(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, "https://provider/live.m3u8?token=fixture", httpFailure(status)))
        }
    }
    @Test fun behindLiveWindowAlwaysSeeksWithinTheCurrentSession() {
        assertEquals(RecoveryAction.LiveEdge, PlaybackRecovery().action(
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW, "https://provider/live.m3u8"))
    }

    @Test fun onlyOneProgressiveFallbackForAnHlsParseError() {
        for (code in listOf(3001, 3002, 3003, 3004)) {
            val recovery = PlaybackRecovery()
            assertEquals(RecoveryAction.Progressive, recovery.action(code, "https://provider/live.M3U8?token=masked"))
            recovery.onRecovery(RecoveryAction.Progressive)
            recovery.onRecovery(RecoveryAction.RestoreHls)
            assertEquals(RecoveryAction.Retry, recovery.action(code, "https://provider/live.m3u8"))
        }
    }

    @Test fun nestedUnrecognizedInputAlsoTriggersFallback() {
        assertEquals(RecoveryAction.Progressive, PlaybackRecovery().action(2000, "https://provider/live.m3u8",
            Exception("outer", UnrecognizedInputFormatException())))
    }

    @Test fun anEstablishedHlsManifestOrReadySessionNeverFallsBackForSegmentErrors() {
        for (ready in listOf(false, true)) {
            val recovery = PlaybackRecovery()
            if (ready) recovery.onReady() else recovery.onManifestLoaded()
            for (code in listOf(3001, 3002, 3003, 3004)) {
                assertEquals(RecoveryAction.Retry, recovery.action(code, "https://provider/live.m3u8"))
            }
            assertEquals(RecoveryAction.Retry, recovery.action(2000, "https://provider/live.m3u8",
                UnrecognizedInputFormatException()))
        }
    }

    @Test fun progressiveFailureBeforeReadyRestoresHlsAndTheFallbackBudgetStaysUsed() {
        val recovery = PlaybackRecovery()
        recovery.onRecovery(RecoveryAction.Progressive)
        for (code in listOf(1002, 2001, 3001, 3002, 4001)) {
            assertEquals(RecoveryAction.RestoreHls, recovery.action(code, "https://provider/live.m3u8"))
        }
        recovery.onRecovery(RecoveryAction.RestoreHls)
        assertEquals(RecoveryAction.Retry, recovery.action(3002, "https://provider/live.m3u8"))
    }

    @Test fun successfulProgressiveModeSurvivesAllLaterRetries() {
        val recovery = PlaybackRecovery()
        recovery.onRecovery(RecoveryAction.Progressive)
        recovery.onReady()
        for (code in listOf(1002, 2001, 3001, 3002, 4001)) {
            assertEquals(RecoveryAction.Retry, recovery.action(code, "https://provider/live.m3u8"))
            recovery.onRecovery(RecoveryAction.Retry)
        }
    }

    @Test fun aNewSessionDoesNotInheritThePreviousSuccessfulModeOrFallbackBudget() {
        val previous = PlaybackRecovery()
        previous.onRecovery(RecoveryAction.Progressive)
        previous.onReady()
        assertEquals(RecoveryAction.Retry, previous.action(3002, "https://provider/live.m3u8"))
        assertEquals(RecoveryAction.Progressive, PlaybackRecovery().action(3002, "https://provider/live.m3u8"))
    }

    @Test fun networkCodecNonHlsAndInvalidAddressesDoNotChangeTheSourceType() {
        for (url in listOf("", "not a URL", "https://provider/live.ts", "https://provider/live.mp4")) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery().action(3002, url))
        }
        for (code in listOf(2001, 2002, 4001, 4003, 5001)) {
            assertEquals(RecoveryAction.Retry, PlaybackRecovery().action(code, "https://provider/live.m3u8"))
        }
    }

    private class UnrecognizedInputFormatException : Exception()
}
