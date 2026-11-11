package dev.firetvlite.iptv.playback

import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StartupResumeTest {
    @Test fun waitsForImportToRestoreCachedRecentChannels() = runBlocking {
        val rows = MutableStateFlow(emptyList<String>())
        var result: Pair<Long, List<String>>? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            result = StartupResume.awaitRecent(flowOf(1L)) { rows }
        }
        assertNull(result)
        rows.value = listOf("last", "previous")
        job.join()
        assertEquals(1L to listOf("last", "previous"), result)
    }

    @Test fun followsAnActiveSourceThatAppearsOrChanges() = runBlocking {
        val result = StartupResume.awaitRecent(flowOf(null, 1L, 2L)) { id ->
            flowOf(if (id == 2L) listOf("restored") else emptyList())
        }
        assertEquals(2L to listOf("restored"), result)
    }

    @Test fun userNavigationCancelsWaitingWithoutOpeningAnything() = runBlocking {
        var resumed = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            StartupResume.awaitRecent(flowOf(1L)) { MutableStateFlow(emptyList<String>()) }
            resumed = true
        }
        job.cancelAndJoin()
        assertFalse(resumed)
    }

    @Test fun queryFailuresAreNotMistakenForAResumableChannel() {
        assertThrows(IOException::class.java) {
            runBlocking {
                StartupResume.awaitRecent<String>(flowOf(1L)) { flow { throw IOException("query failed") } }
            }
        }
    }
}
