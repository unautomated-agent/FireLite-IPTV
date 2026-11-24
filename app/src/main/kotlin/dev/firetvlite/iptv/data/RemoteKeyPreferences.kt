package dev.firetvlite.iptv.data

import android.content.Context
import android.content.SharedPreferences
import dev.firetvlite.iptv.playback.RemoteAlias
import dev.firetvlite.iptv.playback.RemoteKeyLearning
import dev.firetvlite.iptv.playback.RemoteKeyMap
import dev.firetvlite.iptv.playback.RemoteLearnResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class RemoteKeyPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("remote", Context.MODE_PRIVATE))

    private val mutableKeys = MutableStateFlow(RemoteKeyMap(preferences.getInt("menu", 0), preferences.getInt("home", 0)))
    val keys = mutableKeys.asStateFlow()
    private val mutableLearning = MutableStateFlow<RemoteAlias?>(null)
    val learning = mutableLearning.asStateFlow()
    private val mutableResult = MutableStateFlow<Pair<RemoteAlias, RemoteLearnResult>?>(null)
    val result = mutableResult.asStateFlow()
    private val learn = RemoteKeyLearning({ mutableKeys.value }, ::save)

    private fun save(map: RemoteKeyMap) {
        preferences.edit().putInt("menu", map.menu).putInt("home", map.home).apply()
        mutableKeys.value = map
    }

    fun startLearning(alias: RemoteAlias) {
        learn.start(alias)
        mutableResult.value = null
        mutableLearning.value = alias
    }

    fun clear(alias: RemoteAlias) {
        save(mutableKeys.value.with(alias, 0))
        mutableResult.value = null
    }

    fun timeout() = finish(learn.timeout())

    /** True when the press belonged to a learning round and must not reach the screen. */
    fun pressed(keyCode: Int): Boolean = finish(learn.pressed(keyCode))

    private fun finish(outcome: RemoteLearnResult?): Boolean {
        val alias = mutableLearning.value
        if (outcome == null || alias == null) return false
        mutableLearning.value = null
        mutableResult.value = alias to outcome
        return true
    }
}
