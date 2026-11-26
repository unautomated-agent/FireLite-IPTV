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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object Films {
    internal const val VARIANT_PREFIX = "@variant:"
    const val DAY = 86_400_000L
    const val DAILY_BUDGET = 1000
    const val MISS_DAYS = 3
    const val LIVE_TAB = 0
    const val ARCHIVE_TAB = 1
    const val UPCOMING_TAB = 2
    const val FUTURE_TAB = 3
    const val TAB_COUNT = 4
    val zone: ZoneId = ZoneId.of("Europe/Warsaw")
    private val series = Regex("(?iu)\\b(serial\\w*|series|odc\\.?|odcinek|sezon|season|episode|s\\d{1,2}\\s*e\\d+|e\\d+)\\b|\\(\\d+/\\d+\\)")
    private val genre = Regex("(?imu)\\b(sensacja|dramat|komedia|thriller|horror|western|sci-fi|science fiction|kryminał|przygodowy|wojenny|obyczajowy|romans|fantasy|biograficzny|animowany film|action|drama|comedy|crime|adventure|war|romance|biographical|animated film|animated movie|feature film|television film|tv movie)\\b|\\bfilm (fabularny|kinowy|telewizyjny|animowany)\\b|^(films?|movies?|motion pictures?)$")
    private val filmWord = Regex("(?iu)\\b(film\\w*|movies?|motion pictures?)\\b")
    private val notFilm = Regex("(?iu)\\b(wiadomości|news|informac\\w*|magazyn\\w*|publicysty\\w*|teleturniej\\w*|reality|talk[- ]show|show|rozryw\\w*|pogoda|reportaż\\w*|program\\w*)\\b")
    private val subtitle = Regex("\\s*(:|\\s[-–—]\\s|\\().*$")
    private val partSuffix = Regex("(?iu)[\\s,.]*\\b(cz\\.?|część|part)\\s*[0-9ivxl]+\\b.*$")
    private val titleSeparators = Regex("[^\\p{L}\\p{N}]+")
    fun normalized(title: String): String = title.lowercase(Locale.ROOT)
        .replace(titleSeparators, " ").trim()
    fun candidate(p: ProgrammeEntity): Boolean = candidateMetadata(p) && OmdbMissAnalysis.skipReason(p) == null
    internal fun candidateMetadata(p: ProgrammeEntity): Boolean {
        val categories = (p.categories.ifBlank { p.category.orEmpty() })
            .split('\n').joinToString("\n") { it.substringBefore("\",]\"name\":\"").trim().lowercase(Locale.ROOT) }
        return p.stop - p.start >= 80 * 60_000L &&
            p.episodeNum == null && !series.containsMatchIn(p.title + "\n" + p.originalTitle.orEmpty() + "\n" + categories) &&
            !notFilm.containsMatchIn(categories) && (year(p) != null || filmWord.containsMatchIn(categories))
    }
    fun year(p: ProgrammeEntity): Int? = p.year?.takeIf { it in 1000..9999 }
    fun key(p: ProgrammeEntity): String = normalized(p.originalTitle?.takeIf { it.isNotBlank() } ?: p.title) + ":" + p.year
    fun queries(p: ProgrammeEntity): List<Pair<String, Int?>> {
        val titles = listOfNotNull(p.originalTitle?.takeIf {
            it.isNotBlank() && normalized(it) != normalized(p.title)
        }, p.title.takeIf { it.isNotBlank() })
        return titles.map { it to year(p) }
    }
    /** Title without subtitle or part number: "Harry Potter i Insygnia Śmierci: część 1" → "Harry Potter i Insygnia Śmierci". */
    fun simplified(title: String): String? = title.replace(partSuffix, "").replace(subtitle, "").trim()
        .takeIf { normalized(it).isNotEmpty() && normalized(it) != normalized(title) }
    /** Tried in order after the exact [queries] all miss; a search costs two requests. */
    internal fun fallbacks(p: ProgrammeEntity): List<OmdbQuery> {
        val year = year(p)
        val main = queries(p).firstOrNull()?.first ?: return emptyList()
        val short = simplified(main)
        val tolerance = if (partSuffix.containsMatchIn(main)) 0 else 1
        return listOfNotNull(
            OmdbQuery(main, null).takeIf { year != null },
            // Without its part number a title fits every instalment, so then only the exact year may match.
            short?.let { OmdbQuery(it, year, tolerance = tolerance) },
            OmdbQuery(short ?: main, year, search = true, tolerance = tolerance).takeIf { year != null },
        )
    }
    internal fun variantKey(identity: String, title: String, year: Int?, search: Boolean = false): String =
        "$VARIANT_PREFIX$identity:${normalized(title)}:${year ?: "any"}${if (search) ":search" else ""}"
    fun fresh(rating: FilmRatingEntity, now: Long): Boolean = now >= rating.fetchedAt &&
        now - rating.fetchedAt < (if (rating.imdbId == null) MISS_DAYS else 30) * DAY
    fun day(now: Long): Long = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
    fun inWindow(p: ProgrammeEntity, now: Long, tab: Int): Boolean = when (tab) {
        LIVE_TAB -> p.start <= now && p.stop > now
        UPCOMING_TAB -> p.start > now && p.start < now + DAY
        FUTURE_TAB -> p.start >= now + DAY && p.start < now + 2 * DAY
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
    fun matches(year: Int?, tolerance: Int = 1): Boolean = type == "movie" && (year == null || kotlin.math.abs(this.year - year) <= tolerance) &&
        id.matches(Regex("tt[0-9]+"))
}

internal data class OmdbQuery(val title: String, val year: Int?, val search: Boolean = false, val tolerance: Int = 1)

sealed interface OmdbResult {
    data class Found(val movie: OmdbMovie) : OmdbResult
    data object Missing : OmdbResult
    data class Failed(val code: String = "NETWORK") : OmdbResult
}

fun interface OmdbClient {
    suspend fun lookup(key: String, title: String, year: Int?): OmdbResult
    /** Finds the film by title words, picks the result within [tolerance] years and loads it; [second] reserves the second request. */
    suspend fun search(key: String, title: String, year: Int, tolerance: Int, second: suspend () -> Boolean): OmdbResult = OmdbResult.Missing
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
        if (key.isBlank()) emptyMap() else ratings.filter {
            !it.cacheKey.startsWith(Films.VARIANT_PREFIX) && Films.fresh(it, current)
        }.associateBy { it.cacheKey }
    }

class FilmLookup(private val client: OmdbClient, private val store: FilmStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val observer: OmdbObserver = object : OmdbObserver {},
    private val wait: suspend () -> Unit = { kotlinx.coroutines.delay(250) }) {
    var lastError: String? = null
        private set

    internal fun clearError() { lastError = null }

    suspend fun lookup(p: ProgrammeEntity, key: String, now: Long): FilmRatingEntity? {
        lastError = null
        if (key.isBlank()) { fail("NO_KEY"); return null }
        if (!Films.candidate(p)) return null
        val identity = Films.key(p)
        store.cached(identity)?.takeIf { Films.fresh(it, now) }?.let { return it }
        val year = Films.year(p)
        for (query in (Films.queries(p).map { OmdbQuery(it.first, it.second) } + Films.fallbacks(p))
            .distinctBy { Films.variantKey(identity, it.title, it.year, it.search) }) {
            currentCoroutineContext().ensureActive()
            val variant = Films.variantKey(identity, query.title, query.year, query.search)
            if (store.cached(variant)?.let { Films.fresh(it, now) } == true) continue
            wait()
            val reserve: suspend () -> Boolean = { store.reserve(Films.day(clock())).also { if (it) observer.requested(clock()) } }
            if (!reserve()) { fail("LOCAL_BUDGET"); return null }
            val result = if (query.search) client.search(key, query.title, query.year!!, query.tolerance) { wait(); reserve() }
                else client.lookup(key, query.title, query.year)
            observer.completed(result, clock())
            when (result) {
                is OmdbResult.Failed -> { lastError = result.code; return null }
                OmdbResult.Missing -> Unit
                is OmdbResult.Found -> if (result.movie.matches(year, query.tolerance)) {
                    val movie = result.movie
                    return FilmRatingEntity(identity, movie.rating, movie.votes, movie.id, clock()).also { store.save(it) }
                }
            }
            withContext(NonCancellable) {
                store.save(FilmRatingEntity(variant, null, 0L, null, clock()))
            }
        }
        observer.missing(p, clock())
        return FilmRatingEntity(identity, null, 0, null, clock()).also { store.save(it) }
    }

    private fun fail(code: String) {
        lastError = code
        observer.error(code, clock())
    }
}
