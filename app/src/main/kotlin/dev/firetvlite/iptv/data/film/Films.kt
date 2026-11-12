package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.playback.CatchupUrlBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

object Films {
    const val DAY = 86_400_000L
    const val ARCHIVE_TAB = 2
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val series = Regex("(?iu)\\b(serial\\w*|series|odc\\.?|odcinek|sezon|season|episode|s\\d{1,2}\\s*e\\d+|e\\d+)\\b|\\(\\d+/\\d+\\)")
    private val genre = Regex("(?imu)\\b(sensacja|dramat|komedia|thriller|horror|western|sci-fi|science fiction|kryminał|przygodowy|wojenny|obyczajowy|romans|fantasy|biograficzny|animowany film|action|drama|comedy|crime|adventure|war|romance|biographical|animated film|animated movie|feature film|television film|tv movie)\\b|\\bfilm (fabularny|kinowy|telewizyjny|animowany)\\b|^(films?|movies?|motion pictures?)$")
    fun normalized(title: String): String = title.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun candidate(p: ProgrammeEntity): Boolean {
        val categories = (p.categories.ifBlank { p.category.orEmpty() })
            .split('\n').joinToString("\n") { it.substringBefore("\",]\"name\":\"").trim().lowercase(Locale.ROOT) }
        return p.year in 1000..9999 && p.stop - p.start >= 80 * 60_000L &&
            p.episodeNum == null && !series.containsMatchIn(p.title + "\n" + p.originalTitle.orEmpty() + "\n" + categories) &&
            genre.containsMatchIn(categories)
    }
    fun key(p: ProgrammeEntity): String = normalized(p.originalTitle?.takeIf { it.isNotBlank() } ?: p.title) + ":" + p.year
    fun queries(p: ProgrammeEntity): List<Pair<String, Int>> {
        val year = p.year ?: return emptyList()
        val titles = listOfNotNull(p.originalTitle?.takeIf { it.isNotBlank() }, p.title.takeIf { it.isNotBlank() })
            .distinctBy(::normalized)
        val years = if (p.originalTitle.isNullOrBlank()) listOf(year) else listOf(year, year - 1, year + 1)
        return years.flatMap { y -> titles.map { it to y } }
    }
    fun fresh(rating: FilmRatingEntity, now: Long): Boolean = now >= rating.fetchedAt &&
        now - rating.fetchedAt < (if (rating.imdbId == null) 7 else 30) * DAY
    fun day(now: Long): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    fun inWindow(p: ProgrammeEntity, now: Long, tab: Int): Boolean = when (tab) {
        0 -> p.start < now + DAY && p.stop > now
        1 -> p.start >= now + DAY && p.start < now + 2 * DAY
        else -> false
    }
    fun archiveUntil(channel: ChannelEntity, p: ProgrammeEntity): Long? =
        if (CatchupUrlBuilder.isSupported(channel)) p.start + channel.catchupDays.toLong() * DAY else null

    fun archived(channel: ChannelEntity, p: ProgrammeEntity, now: Long): Boolean =
        p.stop > p.start && p.stop <= now && (archiveUntil(channel, p)?.let { it >= now } == true)

    fun archiveWindowStart(channels: List<ChannelEntity>, now: Long): Long = now -
        (channels.filter { CatchupUrlBuilder.isSupported(it) }.maxOfOrNull { it.catchupDays.toLong() } ?: 0) * DAY
    fun time(time: Long): String = DateTimeFormatter.ofPattern("EEE d.MM HH:mm", Locale.forLanguageTag("pl-PL"))
        .withZone(zone).format(Instant.ofEpochMilli(time))
}

data class OmdbMovie(val type: String, val year: Int, val rating: Double?, val votes: Long, val id: String) {
    fun matches(year: Int): Boolean = type == "movie" && kotlin.math.abs(this.year - year) <= 1 &&
        id.matches(Regex("tt[0-9]+"))
}

sealed interface OmdbResult {
    data class Found(val movie: OmdbMovie) : OmdbResult
    data object Missing : OmdbResult
    data class Failed(val code: String = "NETWORK") : OmdbResult
}

fun interface OmdbClient {
    suspend fun lookup(key: String, title: String, year: Int): OmdbResult
}

interface FilmStore {
    suspend fun cached(key: String): FilmRatingEntity?
    suspend fun save(rating: FilmRatingEntity)
    suspend fun reserve(day: Long): Boolean
}

fun observeFilmRatings(rows: Flow<List<FilmRatingEntity>>, keys: Flow<String>, ticks: Flow<Long>,
    now: () -> Long = System::currentTimeMillis): Flow<Map<String, FilmRatingEntity>> =
    combine(rows, keys, ticks) { ratings, key, _ ->
        val current = now()
        if (key.isBlank()) emptyMap() else ratings.filter { Films.fresh(it, current) }.associateBy { it.cacheKey }
    }

class FilmLookup(private val client: OmdbClient, private val store: FilmStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observer: OmdbObserver = object : OmdbObserver {},
    private val wait: suspend () -> Unit = { kotlinx.coroutines.delay(250) }) {
    var lastError: String? = null
        private set

    suspend fun lookup(p: ProgrammeEntity, key: String, now: Long): FilmRatingEntity? {
        lastError = null
        if (key.isBlank()) { fail("NO_KEY"); return null }
        if (!Films.candidate(p)) return null
        val identity = Films.key(p)
        store.cached(identity)?.takeIf { Films.fresh(it, now) }?.let { return it }
        for ((title, year) in Films.queries(p)) {
            wait()
            if (!store.reserve(Films.day(clock()))) { fail("LOCAL_BUDGET"); return null }
            observer.requested(clock())
            val result = client.lookup(key, title, year)
            observer.completed(result, clock())
            when (result) {
                is OmdbResult.Failed -> { lastError = result.code; return null }
                OmdbResult.Missing -> Unit
                is OmdbResult.Found -> if (result.movie.matches(p.year!!)) {
                    val movie = result.movie
                    return FilmRatingEntity(identity, movie.rating, movie.votes, movie.id, clock()).also { store.save(it) }
                }
            }
        }
        return FilmRatingEntity(identity, null, 0, null, clock()).also { store.save(it) }
    }

    private fun fail(code: String) {
        lastError = code
        observer.error(code, clock())
    }
}
