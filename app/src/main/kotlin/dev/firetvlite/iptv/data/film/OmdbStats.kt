package dev.firetvlite.iptv.data.film

data class OmdbStats(val day: Long = Long.MIN_VALUE, val requests: Int = 0, val hits: Int = 0,
    val misses: Int = 0, val lastError: String = "—") {
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
}

interface OmdbObserver {
    fun requested(now: Long) {}
    fun completed(result: OmdbResult, now: Long) {}
    fun error(code: String, now: Long) {}
}
