package dev.firetvlite.iptv.data.film

import android.content.Context
import dev.firetvlite.iptv.BuildConfig
import dev.firetvlite.iptv.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class OmdbPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("omdb", Context.MODE_PRIVATE)
    private val mutableKey = MutableStateFlow(prefs.getString("key", "").orEmpty())
    val key = mutableKey.asStateFlow()
    fun save(key: String) {
        val value = key.trim()
        prefs.edit().putString("key", value).apply()
        mutableKey.value = value
    }
    fun baseUrl(): String = if (BuildConfig.DEBUG) prefs.getString("debug_base_url", null)
        ?: "https://www.omdbapi.com/" else "https://www.omdbapi.com/"
}

fun omdbKeyStatus(configured: Boolean): Int = if (configured) R.string.omdb_key_set else R.string.omdb_key_missing
