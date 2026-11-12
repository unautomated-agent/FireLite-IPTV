package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun enrichFilms(candidates: List<ProgrammeEntity>, key: String, lookup: FilmLookup,
    active: suspend () -> Boolean, now: () -> Long = System::currentTimeMillis): Boolean {
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
