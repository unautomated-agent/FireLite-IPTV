package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class SearchSuggestionsTest {
    private val channel = ChannelEntity(1, 1, 0, "TVN", "", "local:test", null, null, null, null, 0)

    @Test fun cancelledOldQueryCannotPublishPartialResultsFailureOrLoadingAfterNewQuery(): Unit = runBlocking {
        val oldStarted = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val releaseNew = CompletableDeferred<Unit>()
        val oldDone = CompletableDeferred<Unit>()
        val model = SearchSuggestions(this, { _, query, _, partial ->
            if (query == "t") withContext(NonCancellable) {
                oldStarted.complete(Unit)
                releaseOld.await()
                partial(listOf(channel.copy(name = "STALE")))
                oldDone.complete(Unit)
                throw IOException("stale failure")
            }
            newStarted.complete(Unit)
            releaseNew.await()
            SearchResults(listOf(channel))
        }, debounceMs = 0)
        model.request(1, "t")
        oldStarted.await()
        model.request(1, "tv")
        newStarted.await()
        releaseOld.complete(Unit)
        oldDone.await()
        yield()
        assertEquals("tv", model.state.value.query)
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.failed)
        assertTrue(model.state.value.results.channels.isEmpty())
        releaseNew.complete(Unit)
        withTimeout(1000) { while (model.state.value.loading) yield() }
        assertEquals(listOf(channel), model.state.value.results.channels)
        assertFalse(model.state.value.failed)
    }

    @Test fun rapidLettersDebounceToLatestAndEmptyQueryLoadsSuggestions(): Unit = runBlocking {
        val queries = mutableListOf<String>()
        val model = SearchSuggestions(this, { _, query, _, _ -> queries += query; SearchResults(listOf(channel)) }, debounceMs = 20)
        model.request(1, "t")
        model.request(1, "tv")
        model.request(1, "tvn")
        delay(60)
        assertEquals(listOf("tvn"), queries)
        model.request(1, "")
        delay(60)
        assertEquals(listOf("tvn", ""), queries)
        assertEquals(listOf(channel), model.state.value.results.channels)
        model.request(null, "")
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.results.channels.isEmpty())
    }

    @Test fun currentFailureStopsLoadingAndAChangedSourceClearsPriorResults(): Unit = runBlocking {
        val model = SearchSuggestions(this, { source, _, _, partial ->
            if (source == 2L) throw IOException("database")
            partial(listOf(channel))
            SearchResults(listOf(channel))
        }, debounceMs = 0)
        model.request(1, "t")
        withTimeout(1000) { while (model.state.value.loading) yield() }
        assertEquals(listOf(channel), model.state.value.results.channels)
        model.request(2, "t")
        assertTrue(model.state.value.results.channels.isEmpty())
        withTimeout(1000) { while (model.state.value.loading) yield() }
        assertTrue(model.state.value.failed)
        assertTrue(model.state.value.results.channels.isEmpty())
    }
}
