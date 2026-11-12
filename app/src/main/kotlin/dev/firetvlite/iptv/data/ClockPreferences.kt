package dev.firetvlite.iptv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ClockPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("appearance", Context.MODE_PRIVATE))

    private val mutableVisible = MutableStateFlow(preferences.getBoolean("clock_visible", true))
    val visible = mutableVisible.asStateFlow()

    fun setVisible(visible: Boolean) {
        preferences.edit().putBoolean("clock_visible", visible).apply()
        mutableVisible.value = visible
    }
}
