package dev.firetvlite.iptv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much later than the guide time a programme is requested from the archive. A provider's archive runs
 * behind the guide clock, so the guide time itself lands in the last moments of the previous programme.
 */
class ArchiveStartPreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("playback", Context.MODE_PRIVATE))

    private val mutableDelay = MutableStateFlow(
        preferences.getInt("archive_start_delay", DEFAULT).takeIf { it in OPTIONS } ?: DEFAULT)
    /** Seconds. */
    val delay = mutableDelay.asStateFlow()

    /** Moves to the next option, back to the first after the last. */
    fun next() {
        val value = OPTIONS[(OPTIONS.indexOf(mutableDelay.value) + 1) % OPTIONS.size]
        preferences.edit().putInt("archive_start_delay", value).apply()
        mutableDelay.value = value
    }

    /** The archive time to request for a programme that starts at [start]; never one that has not been broadcast yet. */
    fun time(start: Long, now: Long): Long =
        (start + mutableDelay.value * 1000L).takeIf { it < now - MARGIN_MS } ?: start

    companion object {
        val OPTIONS = listOf(0, 15, 30, 45, 60, 90)
        const val DEFAULT = 30
        const val MARGIN_MS = 10_000L
    }
}
