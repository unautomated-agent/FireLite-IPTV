package dev.firetvlite.iptv.data.film

import java.time.Instant

data class ReminderShowing(val channelId: Long, val channelName: String, val start: Long, val stop: Long,
    val title: String, val originalTitle: String?) {
    val key: String get() = "$channelId:$start"
}

sealed interface FilmReminderCard {
    data class Daily(val date: Long, val showings: List<ReminderShowing>) : FilmReminderCard
    data class Soon(val showing: ReminderShowing) : FilmReminderCard
}

object FilmReminders {
    val WATCH_PATTERNS = listOf("harry potter")
    const val TEN_MINUTES = 10 * 60_000L

    fun day(now: Long): Long = Instant.ofEpochMilli(now).atZone(Films.zone).toLocalDate().toEpochDay()

    fun tomorrow(now: Long): Long = Instant.ofEpochMilli(now).atZone(Films.zone).toLocalDate()
        .plusDays(1).atStartOfDay(Films.zone).toInstant().toEpochMilli()

    fun queryUntil(now: Long): Long = maxOf(tomorrow(now), now + TEN_MINUTES + 1)

    private fun candidates(showings: List<ReminderShowing>, now: Long): List<ReminderShowing> = showings.filter { showing ->
        showing.stop > now &&
            WATCH_PATTERNS.any { pattern ->
                Films.normalized(showing.title).contains(pattern) || Films.normalized(showing.originalTitle.orEmpty()).contains(pattern)
            }
    }.distinctBy { it.key }.sortedWith(compareBy<ReminderShowing> { it.start }.thenBy { it.channelId })

    private fun daily(showings: List<ReminderShowing>, now: Long): List<ReminderShowing> = showings
        .filter { it.start <= now || day(it.start) == day(now) }

    fun matches(showings: List<ReminderShowing>, now: Long): List<ReminderShowing> = daily(candidates(showings, now), now)

    fun next(showings: List<ReminderShowing>, now: Long, acknowledgedDate: Long?, shown: Set<String>,
        watchingLiveChannelId: Long? = null): FilmReminderCard? {
        val candidates = candidates(showings, now)
        val soon = candidates.firstOrNull {
            now >= it.start - TEN_MINUTES && now < it.start && it.key !in shown && it.channelId != watchingLiveChannelId
        }
        if (soon != null) return FilmReminderCard.Soon(soon)
        val matches = daily(candidates, now)
        return if (matches.isNotEmpty() && acknowledgedDate != day(now)) FilmReminderCard.Daily(day(now), matches) else null
    }

    fun recentKeys(keys: Set<String>, now: Long): Set<String> = keys.filterTo(mutableSetOf()) {
        val start = it.substringAfterLast(':').toLongOrNull()
        start != null && start >= now - 2 * Films.DAY
    }
}
