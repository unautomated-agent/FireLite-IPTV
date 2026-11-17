package dev.firetvlite.iptv.playback

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.*
import org.junit.Test

class PlaybackWorkTest {
    @Test fun pendingDatabaseSelectionCannotPlayAfterHomeAndFreshWorkCanResume(): Unit = runBlocking {
        val work = PlaybackWork(this)
        val database = CompletableDeferred<Unit>()
        var plays = 0
        val controller = PlaybackController(this) { error("No player should be created") }
        val lifecycle = PlaybackLifecycle(controller, work::pause, work::resume)
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = error("Unused")
        }
        try {
            val pending = work.launch { database.await(); plays++ }
            yield()
            lifecycle.onPause(owner)
            lifecycle.onStop(owner)
            database.complete(Unit)
            pending.join()
            assertTrue(pending.isCancelled)
            assertEquals(0, plays)
            work.launch { plays++ }.join()
            assertEquals(0, plays)
            lifecycle.onResume(owner)
            work.launch { plays++ }.join()
            assertEquals(1, plays)
        } finally { work.pause() }
    }

    @Test fun interruptedStartupCanBeRestartedButItsOldResultCannotPlay(): Unit = runBlocking {
        val work = PlaybackWork(this)
        val database = CompletableDeferred<Unit>()
        var startupPending = true
        var plays = 0
        fun restore() = work.launch {
            database.await()
            val selection = work.launch { yield(); plays++ }
            selection.join()
            startupPending = false
        }
        try {
            val startup = restore()
            yield()
            work.pause()
            startup.join()
            assertTrue(startupPending)
            database.complete(Unit)
            work.resume()
            restore().join()
            assertFalse(startupPending)
            assertEquals(1, plays)
        } finally { work.pause() }
    }

    @Test fun selectionErrorsRemainFailuresAndDoNotDisableFutureWork(): Unit = runBlocking {
        val failures = mutableListOf<Throwable>()
        val work = PlaybackWork(CoroutineScope(coroutineContext + CoroutineExceptionHandler { _, error -> failures += error }))
        var completed = false
        try {
            val failed = work.launch { error("database") }
            failed.join()
            assertTrue(failed.isCancelled)
            assertEquals("database", failures.single().message)
            work.launch { completed = true }.join()
            assertTrue(completed)
        } finally { work.pause() }
    }
}
