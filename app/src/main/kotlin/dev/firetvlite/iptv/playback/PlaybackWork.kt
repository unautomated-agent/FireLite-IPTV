package dev.firetvlite.iptv.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class PlaybackWork(private val scope: CoroutineScope) {
    private var generation = SupervisorJob(scope.coroutineContext[Job])
    var resumed = true
        private set

    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(generation) {
        if (resumed) block()
    }

    fun pause() {
        resumed = false
        generation.cancel()
    }

    fun resume() {
        if (resumed) return
        generation = SupervisorJob(scope.coroutineContext[Job])
        resumed = true
    }
}
