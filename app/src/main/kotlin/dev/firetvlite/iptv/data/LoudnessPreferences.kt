package dev.firetvlite.iptv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LoudnessPreferences(
    scope: CoroutineScope,
    preferences: () -> SharedPreferences,
    io: CoroutineDispatcher = Dispatchers.IO,
    main: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    constructor(context: Context, scope: CoroutineScope) : this(scope, {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    })

    private val mutableEnabled = MutableStateFlow(true)
    val enabled = mutableEnabled.asStateFlow()
    private val changes = Channel<Boolean>(Channel.CONFLATED)
    private var changed = false

    init {
        scope.launch(io) {
            val store = runCatching { preferences() }.getOrNull()
            val saved = runCatching { store?.getBoolean("loudness_enabled", true) ?: true }.getOrDefault(true)
            withContext(main) { if (!changed) mutableEnabled.value = saved }
            for (value in changes) runCatching { store?.edit()?.putBoolean("loudness_enabled", value)?.apply() }
        }
    }

    fun setEnabled(value: Boolean) {
        changed = true
        mutableEnabled.value = value
        changes.trySend(value)
    }
}
