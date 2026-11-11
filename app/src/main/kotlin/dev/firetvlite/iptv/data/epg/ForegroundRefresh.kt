package dev.firetvlite.iptv.data.epg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ForegroundRefresh(
    private val scope: CoroutineScope,
    private val waitForNextCheck: suspend () -> Unit = { delay(60L * 60 * 1000) },
    private val check: suspend () -> Unit,
) {
    private var job: Job? = null

    fun setForeground(foreground: Boolean) {
        if (!foreground) {
            job?.cancel()
            job = null
        } else if (job?.isActive != true) {
            job = scope.launch {
                while (true) {
                    try {
                        check()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        // Retry at the next foreground check.
                    }
                    waitForNextCheck()
                }
            }
        }
    }
}
