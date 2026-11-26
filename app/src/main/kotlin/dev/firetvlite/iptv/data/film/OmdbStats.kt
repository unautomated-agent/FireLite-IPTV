package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.data.db.ProgrammeEntity

data class OmdbStats(val day: Long = Long.MIN_VALUE, val requests: Int = 0, val hits: Int = 0,
    val misses: Int = 0, val lastError: String = "—", val missTypes: Map<OmdbMissType, Int> = emptyMap(),
    val missTitles: List<String> = emptyList()) {
    fun today(now: Long): OmdbStats = if (day == Films.day(now)) this else OmdbStats(Films.day(now))
    fun requested(now: Long): OmdbStats = today(now).let { it.copy(requests = it.requests + 1) }
    fun completed(result: OmdbResult, now: Long): OmdbStats = today(now).let {
        when (result) {
            is OmdbResult.Found -> it.copy(hits = it.hits + 1)
            OmdbResult.Missing -> it.copy(misses = it.misses + 1)
            is OmdbResult.Failed -> it.error(result.code)
        }
    }
    fun error(code: String): OmdbStats = copy(lastError = code.takeIf { it.matches(Regex("[A-Z_0-9]{1,24}")) } ?: "UNKNOWN")
    fun missing(programme: ProgrammeEntity, now: Long): OmdbStats = today(now).let {
        val type = OmdbMissAnalysis.primary(programme)
        val title = listOfNotNull(programme.title, programme.originalTitle?.takeIf { o -> o.isNotBlank() && o != programme.title },
            programme.year?.toString()).joinToString(" | ").replace('\n', ' ').take(80)
        it.copy(missTypes = it.missTypes + (type to ((it.missTypes[type] ?: 0) + 1).coerceAtMost(999_999)),
            missTitles = (it.missTitles - title + title).takeLast(MISS_TITLES))
    }
    /** One report line per film that no OMDb query found today, newest first. */
    fun missLines(): List<String> = missTitles.reversed().map { "OMDb brak: $it" }
    fun missSummary(): String = "OMDb braki wg typu: " + missTypes.entries.filter { it.value > 0 }
        .sortedWith(compareByDescending<Map.Entry<OmdbMissType, Int>> { it.value }.thenBy { it.key.ordinal })
        .take(3).joinToString("; ") { "${it.key.label}=${it.value}" }.ifEmpty { "brak nowych danych" }
}

private const val MISS_TITLES = 40

interface OmdbObserver {
    fun requested(now: Long) {}
    fun completed(result: OmdbResult, now: Long) {}
    fun error(code: String, now: Long) {}
    fun missing(programme: ProgrammeEntity, now: Long) {}
}
