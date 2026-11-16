package dev.firetvlite.iptv.update

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LaunchUpdateCheckTest {
    private val release = ReleaseInfo("build-42", 42, 1042, "https://github.com/owner/repo/releases/download/build-42/app.apk", 1)

    @Test fun completedFirstFrameStillWaitsForDeferredStartupWork() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val ready = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        try {
            LaunchUpdateCheck(scope, MutableStateFlow(true), 1041, awaitStartup = { ready.await() }) {
                calls.incrementAndGet(); ReleaseParser.Result.Found(release)
            }
            delay(50)
            assertEquals(0, calls.get())
            ready.complete(Unit)
            withTimeout(2_000) { while (calls.get() == 0) delay(10) }
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun firstFrameStartsExactlyOneOffControllerCheckAndOnlyNewerBuildIsAnnounced() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val frames = MutableStateFlow(false)
        val calls = AtomicInteger()
        val controllerThread = Thread.currentThread()
        try {
            val checker = LaunchUpdateCheck(scope, frames, 1041, fallbackMs = 2_000) {
                assertNotSame(controllerThread, Thread.currentThread())
                calls.incrementAndGet()
                ReleaseParser.Result.Found(release)
            }
            delay(40)
            assertEquals(0, calls.get())
            assertNull(checker.available.value)
            frames.value = true
            withTimeout(2_000) { while (checker.available.value == null) delay(10) }
            assertEquals(release, checker.available.value)
            frames.value = false
            frames.value = true
            delay(60)
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }

    @Test fun noStreamUsesFallbackButAnInitialStreamStillStartingWaitsForRender() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val noStreamCalls = AtomicInteger()
            LaunchUpdateCheck(scope, MutableStateFlow(false), 1041, fallbackMs = 60) {
                noStreamCalls.incrementAndGet(); ReleaseParser.Result.Found(release)
            }
            withTimeout(2_000) { while (noStreamCalls.get() == 0) delay(10) }
            val frames = MutableStateFlow(false)
            val streamingCalls = AtomicInteger()
            LaunchUpdateCheck(scope, frames, 1041, streamActive = { true }, fallbackMs = 30) {
                streamingCalls.incrementAndGet(); ReleaseParser.Result.Found(release)
            }
            delay(150)
            assertEquals(0, streamingCalls.get())
            frames.value = true
            withTimeout(2_000) { while (streamingCalls.get() == 0) delay(10) }
            assertEquals(1, streamingCalls.get())
        } finally { scope.cancel() }
    }

    @Test fun noNewReleaseEmptyResultsAndUnexpectedExceptionsStaySilent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val checks = listOf<() -> ReleaseParser.Result>(
                { ReleaseParser.Result.Found(release) }, { ReleaseParser.Result.NoApk },
                { ReleaseParser.Result.UnknownTag }, { throw IllegalStateException("bad JSON") },
                { throw java.io.IOException("offline") },
            ).map { fetch ->
                val calls = AtomicInteger()
                val checker = LaunchUpdateCheck(scope, MutableStateFlow(true), 1042) { calls.incrementAndGet(); fetch() }
                checker to calls
            }
            withTimeout(2_000) { while (checks.any { it.second.get() == 0 }) delay(10) }
            delay(80)
            checks.forEach { assertNull(it.first.available.value); assertEquals(1, it.second.get()) }
        } finally { scope.cancel() }
    }

    @Test fun cancelledLaunchDoesNotStartNetworkWork() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = AtomicInteger()
        val checker = LaunchUpdateCheck(scope, MutableStateFlow(false), 1041, fallbackMs = 100) {
            calls.incrementAndGet(); ReleaseParser.Result.Found(release)
        }
        scope.cancel()
        delay(160)
        assertEquals(0, calls.get())
        assertNull(checker.available.value)
    }

    @Test fun networkClientIsIndependentBoundedAndGitHubOnlyWithoutRedirectsOrPlaybackGate() {
        val source = File("src/main/kotlin/dev/firetvlite/iptv/update/UpdateRepository.kt").readText()
            .substringBefore("@Throws")
        assertTrue(source.contains("OkHttpClient.Builder()"))
        assertTrue(source.contains("connectTimeout(3, TimeUnit.SECONDS)"))
        assertTrue(source.contains("callTimeout(5, TimeUnit.SECONDS)"))
        assertTrue(source.contains("followRedirects(false)"))
        assertTrue(source.contains("https://api.github.com/repos/"))
        assertFalse(source.contains("PlaybackHttp"))
        assertFalse(source.contains("connectionGate"))
        val viewModel = File("src/main/kotlin/dev/firetvlite/iptv/update/UpdateViewModel.kt").readText()
        assertFalse(viewModel.substringAfter("init {").substringBefore("fun showAutomaticUpdate").contains("checkForUpdate()"))
    }
}
