package dev.firetvlite.iptv

internal class MainThreadWatchdog(
    private val post: (() -> Unit) -> Unit,
    private val stack: () -> String,
    private val report: (Long, String) -> Unit,
    private val thresholdMs: Long = 3_000,
) {
    private class Pulse(val sentAt: Long) {
        @Volatile var acknowledged = false
        var reported = false
    }
    private var pending: Pulse? = null

    fun tick(now: Long, foreground: Boolean) {
        if (!foreground) {
            pending = null
            return
        }
        val pulse = pending
        if (pulse == null || pulse.acknowledged) {
            val next = Pulse(now)
            pending = next
            post { next.acknowledged = true }
        } else if (!pulse.reported && now - pulse.sentAt >= thresholdMs) {
            pulse.reported = true
            report(now - pulse.sentAt, stack())
        }
    }
}
