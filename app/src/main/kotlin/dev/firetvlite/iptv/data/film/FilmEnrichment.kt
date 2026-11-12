package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class FilmWindowRequest(val sourceId: Long, val keyRevision: Long) {
    fun matches(sourceId: Long?, keyRevision: Long): Boolean =
        this.sourceId == sourceId && this.keyRevision == keyRevision
}

internal class FilmWindowRequests {
    private val mutablePending = MutableStateFlow<FilmWindowRequest?>(null)
    val pending = mutablePending.asStateFlow()
    fun request(sourceId: Long, keyRevision: Long) {
        mutablePending.value = FilmWindowRequest(sourceId, keyRevision)
    }
    fun complete(request: FilmWindowRequest) { mutablePending.compareAndSet(request, null) }
}

internal suspend fun enrichFilms(candidates: List<ProgrammeEntity>, key: String, lookup: FilmLookup,
    active: suspend () -> Boolean, now: () -> Long = System::currentTimeMillis): Boolean {
    lookup.clearError()
    var retry = false
    for (programme in candidates) {
        currentCoroutineContext().ensureActive()
        if (!active()) break
        if (lookup.lookup(programme, key, now()) == null) {
            if (lookup.lastError in setOf("NO_KEY", "AUTH", "REMOTE_LIMIT", "LOCAL_BUDGET", "HTTP_401", "HTTP_403", "HTTP_429")) break
            retry = true
        }
    }
    return retry
}
