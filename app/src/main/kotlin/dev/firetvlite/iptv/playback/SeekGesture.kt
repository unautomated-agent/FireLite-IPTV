package dev.firetvlite.iptv.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class SeekGesture {
    private var held: TvKey? = null
    private var started = 0L
    private var lastStep = 0L
    private var delta = 0L

    fun press(key: TvKey, now: Long, repeat: Boolean) {
        val base = when (key) {
            TvKey.Left -> -10_000L
            TvKey.Right -> 10_000L
            TvKey.Rewind -> -60_000L
            TvKey.FastForward -> 60_000L
            else -> return
        }
        if (held != key || !repeat) {
            held = key
            started = now
        } else if (now - lastStep < 200) return
        lastStep = now
        val multiplier = when {
            now - started >= 4_000 -> 8
            now - started >= 2_000 -> 4
            now - started >= 1_000 -> 2
            else -> 1
        }
        delta += base * multiplier
    }

    fun release(key: TvKey): Long? {
        if (held != key) return null
        return delta.also { cancel() }
    }

    fun cancel() { held = null; delta = 0L }
}

internal class SeekDebouncer(private val scope: CoroutineScope, private val commit: (Long) -> Unit,
    private val delayMs: Long = 300L) {
    private var pending = 0L
    private var job: Job? = null

    fun hold() { job?.cancel(); job = null }

    fun release(delta: Long) {
        hold()
        pending += delta
        job = scope.launch {
            delay(delayMs)
            val target = pending
            pending = 0L
            job = null
            if (target != 0L) commit(target)
        }
    }

    fun cancel() { hold(); pending = 0L }
}
