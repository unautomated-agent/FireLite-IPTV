package dev.firetvlite.iptv.playback

internal class StartupTiming(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    enum class Phase {
        STOPPING_PREVIOUS, CLOSE_GRACE, WAITING_PREVIOUS_CONNECTION, PREPARING_BUFFERING,
        RECOVERY_DELAY, WAITING_RECOVERY_CONNECTION,
    }

    private val started = clock()
    private var phaseStarted = started
    private var phase = Phase.STOPPING_PREVIOUS
    private val elapsed = mutableMapOf<Phase, Long>()

    val waitingForPrevious: Boolean
        get() = phase in listOf(Phase.STOPPING_PREVIOUS, Phase.CLOSE_GRACE, Phase.WAITING_PREVIOUS_CONNECTION)

    fun enter(next: Phase) {
        val now = clock()
        elapsed[phase] = (elapsed[phase] ?: 0) + now - phaseStarted
        phaseStarted = now
        phase = next
    }

    fun detail(): String {
        val now = clock()
        return "Startup: phase=$phase; totalMs=${now - started}\n" + Phase.entries.joinToString("\n") {
            val duration = (elapsed[it] ?: 0) + if (it == phase) now - phaseStarted else 0
            "$it=${duration}ms"
        }
    }
}
