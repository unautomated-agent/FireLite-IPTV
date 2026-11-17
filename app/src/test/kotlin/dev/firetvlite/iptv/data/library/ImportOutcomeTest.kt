package dev.firetvlite.iptv.data.library

import dev.firetvlite.iptv.data.playlist.EmptyPlaylistException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ImportOutcomeTest {
    private fun outcome(import: suspend () -> Int) = runBlocking { LibraryRepository.importOutcome(import) }

    @Test fun successReportsTheChannelCount() = assertEquals(ImportState.Done(3), outcome { 3 })

    @Test fun knownFailuresKeepTheirReason() {
        assertEquals(ImportState.Failed(ImportFailure.EmptyPlaylist), outcome { throw EmptyPlaylistException() })
        assertEquals(ImportState.Failed(ImportFailure.Network), outcome { throw IOException("x") })
    }

    @Test fun unexpectedErrorsBecomeAFailedStateInsteadOfEscaping() {
        assertEquals(ImportState.Failed(ImportFailure.Unexpected), outcome { throw ClassCastException("x") })
        assertEquals(ImportState.Failed(ImportFailure.Unexpected), outcome { throw IllegalStateException("x") })
    }

    @Test fun cancellationIsNotSwallowed() {
        assertThrows(CancellationException::class.java) { outcome { throw CancellationException("x") } }
    }
}
