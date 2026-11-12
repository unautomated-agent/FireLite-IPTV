package dev.firetvlite.iptv.data.film

import android.content.Context
import android.content.SharedPreferences
import dev.firetvlite.iptv.BuildConfig
import dev.firetvlite.iptv.R
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmdbPreferences internal constructor(private val prefs: SharedPreferences,
    private val clock: () -> Long = System::currentTimeMillis) : OmdbObserver {
    constructor(context: Context) : this(context.getSharedPreferences("omdb", Context.MODE_PRIVATE))
    private val mutableKey = MutableStateFlow(prefs.getString("key", "").orEmpty())
    val key = mutableKey.asStateFlow()
    private val mutableRefresh = MutableStateFlow(0L)
    val refresh = mutableRefresh.asStateFlow()
    private val mutableError = MutableStateFlow(prefs.getString("current_error", null))
    val currentError = mutableError.asStateFlow()
    private val mutableStats = MutableStateFlow(OmdbStats(prefs.getLong("stats_day", Long.MIN_VALUE),
        prefs.getInt("requests", 0), prefs.getInt("hits", 0), prefs.getInt("misses", 0),
        prefs.getString("last_error", "—").orEmpty()).today(clock()))
    val stats = mutableStats.asStateFlow()

    init { quotaDelay(clock()) }

    @Synchronized
    private fun updateStats(change: (OmdbStats) -> OmdbStats) {
        val value = change(mutableStats.value)
        prefs.edit().putLong("stats_day", value.day).putInt("requests", value.requests)
            .putInt("hits", value.hits).putInt("misses", value.misses).putString("last_error", value.lastError).apply()
        mutableStats.value = value
    }
    override fun requested(now: Long) = updateStats { it.requested(now) }
    override fun completed(result: OmdbResult, now: Long) {
        updateStats { it.completed(result, now) }
        if (result == OmdbResult.Failed("REMOTE_LIMIT")) pauseQuota(now)
        setError((result as? OmdbResult.Failed)?.code)
    }
    override fun error(code: String, now: Long) {
        updateStats { it.today(now).error(code) }
        if (code == "REMOTE_LIMIT") pauseQuota(now)
        setError(code)
    }
    private fun setError(code: String?) {
        val safe = code?.let { OmdbStats().error(it).lastError }
        prefs.edit().putString("current_error", safe).apply()
        mutableError.value = safe
    }
    fun save(key: String): Boolean {
        val value = normalizeOmdbKey(key) ?: return false
        if (value != mutableKey.value) prefs.edit().putLong("quota_until", 0).apply()
        prefs.edit().putString("key", value).apply()
        mutableKey.value = value
        setError(if (quotaDelay(clock()) > 0) "REMOTE_LIMIT" else null)
        mutableRefresh.value += 1
        return true
    }
    private fun pauseQuota(now: Long) {
        val until = Instant.ofEpochMilli(now).atZone(Films.zone).toLocalDate().plusDays(1)
            .atStartOfDay(Films.zone).toInstant().toEpochMilli()
        prefs.edit().putLong("quota_until", until).apply()
    }
    fun quotaDelay(now: Long): Long {
        val remaining = (prefs.getLong("quota_until", 0) - now).coerceAtLeast(0)
        if (remaining == 0L && mutableError.value == "REMOTE_LIMIT") setError(null)
        return remaining
    }
    fun baseUrl(): String = if (BuildConfig.DEBUG) prefs.getString("debug_base_url", null)
        ?: "https://www.omdbapi.com/" else "https://www.omdbapi.com/"
}

fun omdbKeyStatus(configured: Boolean): Int = if (configured) R.string.omdb_key_set else R.string.omdb_key_missing
