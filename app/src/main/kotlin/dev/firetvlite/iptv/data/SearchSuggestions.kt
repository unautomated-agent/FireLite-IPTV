package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.db.ChannelEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class SuggestionState(val generation: Long = 0, val sourceId: Long? = null, val query: String = "",
    val results: SearchResults = SearchResults(), val loading: Boolean = false, val failed: Boolean = false, val at: Long = 0)

internal class SearchSuggestions(private val scope: CoroutineScope,
    private val search: suspend (Long, String, Long, (List<ChannelEntity>) -> Unit) -> SearchResults,
    private val debounceMs: Long = 150, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow(SuggestionState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null

    fun request(sourceId: Long?, query: String) {
        val generation = mutableState.value.generation + 1
        val now = clock()
        mutableState.value = SuggestionState(generation, sourceId, query, loading = sourceId != null, at = now)
        job?.cancel()
        if (sourceId == null) return
        job = scope.launch {
            fun publish(change: (SuggestionState) -> SuggestionState) {
                mutableState.update { if (it.generation == generation) change(it) else it }
            }
            try {
                delay(debounceMs)
                val results = search(sourceId, query, now) { channels ->
                    publish { it.copy(results = SearchResults(channels)) }
                }
                publish { it.copy(results = results) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publish { it.copy(failed = true) }
            } finally {
                publish { it.copy(loading = false) }
            }
        }
    }
}
