package dev.firetvlite.iptv.data.film

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ReminderAcknowledgements(val date: Long? = null, val shown: Set<String> = emptySet())

class ReminderPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("film_reminders", Context.MODE_PRIVATE))

    private val mutableState = MutableStateFlow(ReminderAcknowledgements(
        preferences.getLong("acknowledged_date", Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE },
        preferences.getStringSet("shown", emptySet()).orEmpty().toSet(),
    ))
    val state = mutableState.asStateFlow()

    fun acknowledge(date: Long) {
        preferences.edit().putLong("acknowledged_date", date).apply()
        mutableState.value = mutableState.value.copy(date = date)
    }

    fun markShown(showing: ReminderShowing, now: Long) = saveKeys(FilmReminders.recentKeys(state.value.shown, now) + showing.key)

    fun prune(now: Long) {
        val keys = FilmReminders.recentKeys(state.value.shown, now)
        if (keys != state.value.shown) saveKeys(keys)
    }

    private fun saveKeys(keys: Set<String>) {
        preferences.edit().putStringSet("shown", keys).apply()
        mutableState.value = mutableState.value.copy(shown = keys)
    }
}
