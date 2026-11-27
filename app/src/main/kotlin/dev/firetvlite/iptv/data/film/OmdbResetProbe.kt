package dev.firetvlite.iptv.data.film

import java.time.Instant

/**
 * One-off measurement of the hour at which OMDb resets its own daily counter. After OMDb refuses a request
 * because of the limit, one request is sent every [INTERVAL] until OMDb answers again; the last refusal and
 * the first answer bracket the reset and are sent as a report.
 */
data class OmdbResetProbe(val refusedAt: Long = 0, val done: Boolean = false) {
    val active: Boolean get() = !done && refusedAt > 0

    fun refused(now: Long): OmdbResetProbe = if (done) this else copy(refusedAt = now)

    /** OMDb answered. Finished only when the last refusal is recent; a wider bracket is reported and measured again next time. */
    fun answered(now: Long): Pair<OmdbResetProbe, String?> {
        if (!active || now < refusedAt) return this to null
        val gap = now - refusedAt
        val precise = gap <= MAX_GAP
        return OmdbResetProbe(done = precise) to "OMDb reset licznika (${if (precise) "pomiar" else "niedokładny"}): " +
            "odmowa ${Instant.ofEpochMilli(refusedAt)}, odpowiedź ${Instant.ofEpochMilli(now)}, odstęp ${gap / 60_000} min"
    }

    /** How long to wait before the next probe request; [probedAt] is the last attempt, answered or not. */
    fun delay(now: Long, probedAt: Long): Long = (maxOf(refusedAt, probedAt) + INTERVAL - now).coerceIn(0, INTERVAL)

    companion object {
        const val INTERVAL = 10 * 60_000L
        const val MAX_GAP = 15 * 60_000L
        /** A film every OMDb key can find; the answer itself is not used. */
        const val TITLE = "Casablanca"
        const val YEAR = 1942
    }
}
