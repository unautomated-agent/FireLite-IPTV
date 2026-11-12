package dev.firetvlite.iptv.data.film

import android.content.Context
import android.content.SharedPreferences
import dev.firetvlite.iptv.BuildConfig
import dev.firetvlite.iptv.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmdbPreferences internal constructor(private val prefs: SharedPreferences,
    clock: () -> Long = System::currentTimeMillis) : OmdbObserver {
    constructor(context: Context) : this(context.getSharedPreferences("omdb", Context.MODE_PRIVATE))
    private val mutableKey = MutableStateFlow(prefs.getString("key", "").orEmpty())
    val key = mutableKey.asStateFlow()
    private val mutableStats = MutableStateFlow(OmdbStats(prefs.getLong("stats_day", Long.MIN_VALUE),
        prefs.getInt("requests", 0), prefs.getInt("hits", 0), prefs.getInt("misses", 0),
        prefs.getString("last_error", "—").orEmpty()).today(clock()))
    val stats = mutableStats.asStateFlow()

    @Synchronized
    private fun updateStats(change: (OmdbStats) -> OmdbStats) {
        val value = change(mutableStats.value)
        prefs.edit().putLong("stats_day", value.day).putInt("requests", value.requests)
            .putInt("hits", value.hits).putInt("misses", value.misses).putString("last_error", value.lastError).apply()
        mutableStats.value = value
    }
    override fun requested(now: Long) = updateStats { it.requested(now) }
    override fun completed(result: OmdbResult, now: Long) = updateStats { it.completed(result, now) }
    override fun error(code: String, now: Long) = updateStats { it.today(now).error(code) }
    fun save(key: String) {
        val value = key.trim()
        prefs.edit().putString("key", value).apply()
        mutableKey.value = value
    }
    fun baseUrl(): String = if (BuildConfig.DEBUG) prefs.getString("debug_base_url", null)
        ?: "https://www.omdbapi.com/" else "https://www.omdbapi.com/"
}

fun omdbKeyStatus(configured: Boolean): Int = if (configured) R.string.omdb_key_set else R.string.omdb_key_missing
