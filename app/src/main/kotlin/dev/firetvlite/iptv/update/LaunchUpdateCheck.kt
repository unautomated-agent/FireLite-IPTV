package dev.firetvlite.iptv.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal class LaunchUpdateCheck(
    scope: CoroutineScope,
    firstFrame: StateFlow<Boolean>,
    installedCode: Long,
    streamActive: () -> Boolean = { false },
    fallbackMs: Long = 10_000,
    io: CoroutineDispatcher = Dispatchers.IO,
    awaitStartup: suspend () -> Unit = {},
    fetch: () -> ReleaseParser.Result,
) {
    private val mutable = MutableStateFlow<ReleaseInfo?>(null)
    val available = mutable.asStateFlow()

    init {
        scope.launch {
            awaitStartup()
            withTimeoutOrNull(fallbackMs) { firstFrame.first { it } }
            while (!firstFrame.value && streamActive()) delay(100)
            mutable.value = withContext(io) {
                try {
                    (fetch() as? ReleaseParser.Result.Found)?.release?.takeIf {
                        ReleaseParser.isNewer(installedCode, it)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
