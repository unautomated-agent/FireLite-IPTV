package dev.firetvlite.iptv.data.film

import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.FilmBudgetEntity
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.epg.EpgRepository
import dev.firetvlite.iptv.data.epg.EpgState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart

data class FilmShowing(val channel: ChannelEntity, val programme: ProgrammeEntity, val rating: FilmRatingEntity?) {
    val identity: String get() = "${channel.id}:${programme.start}"
}

fun ratingCandidates(showings: List<FilmShowing>, now: Long, futureWindow: Boolean = false): List<ProgrammeEntity> = showings
    .filter { Films.candidate(it.programme) &&
        (if (futureWindow) Films.inWindow(it.programme, now, 1)
        else Films.inWindow(it.programme, now, 0) || Films.archived(it.channel, it.programme, now)) }
    .sortedWith(compareBy<FilmShowing> { ratingPriority(it, now) }
        .thenBy { if (ratingPriority(it, now) == 1) -it.programme.stop else it.programme.start }
        .thenBy { it.channel.position }.thenBy { it.channel.id })
    .map { it.programme }
    .distinctBy(Films::key)

internal fun enrichmentCandidates(showings: List<FilmShowing>, now: Long, futureRequested: Boolean): List<ProgrammeEntity> =
    ((if (futureRequested) ratingCandidates(showings, now, futureWindow = true) else emptyList()) +
        ratingCandidates(showings, now)).distinctBy(Films::key)

internal fun futureWindowComplete(retry: Boolean, lastError: String?, hasCandidates: Boolean, epgRunning: Boolean): Boolean =
    !retry && lastError == null && (hasCandidates || !epgRunning)

private fun ratingPriority(showing: FilmShowing, now: Long): Int = when {
    Films.inWindow(showing.programme, now, 0) -> 0
    Films.archived(showing.channel, showing.programme, now) -> 1
    else -> 2
}

fun rankedFilms(films: List<FilmShowing>, now: Long, tab: Int): List<FilmShowing> = films
    .filter { Films.candidate(it.programme) && if (tab == Films.ARCHIVE_TAB) Films.archived(it.channel, it.programme, now)
        else Films.inWindow(it.programme, now, tab) }
    .distinctBy { it.identity }
    .sortedWith(compareByDescending<FilmShowing> { it.rating?.imdbRating ?: -1.0 }
        .thenByDescending { it.rating?.imdbVotes ?: 0L }.thenBy { it.programme.start }.thenBy { it.channel.id })

@OptIn(ExperimentalCoroutinesApi::class)
class FilmRepository(private val database: FireLiteDatabase, val preferences: OmdbPreferences,
    scope: CoroutineScope, epg: EpgRepository) {
    private val futureWindows = FilmWindowRequests()
    fun requestFutureFilms(sourceId: Long) {
        if (preferences.key.value.isNotBlank()) futureWindows.request(sourceId, preferences.refresh.value)
    }
    private val dao = database.filmDao()
    private val lookup = FilmLookup(OmdbHttpClient(preferences::baseUrl), object : FilmStore {
        override suspend fun cached(key: String) = dao.cached(key)
        override suspend fun save(rating: FilmRatingEntity) = dao.save(rating)
        override suspend fun reserve(day: Long): Boolean = database.withTransaction {
            dao.budget(FilmBudgetEntity(day))
            dao.reserve(day) == 1
        }
    }, observer = preferences)
    private val mutableEnriching = MutableStateFlow(false)
    val enriching = mutableEnriching.asStateFlow()
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
    val ratings = observeFilmRatings(dao.observeRatings(), preferences.key, clock)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val catalogue = database.sourceDao().observeActive().flatMapLatest { source ->
        (if (source == null) flowOf(FilmCatalogue(loaded = true)) else combine(clock, database.epgDao().filmChannels(source.id)) { now, channels ->
            now to channels
        }.flatMapLatest { (now, channels) ->
            combine(database.epgDao().filmWindow(source.id, Films.archiveWindowStart(channels, now), now + 2 * Films.DAY),
                ratings, database.epgDao().observeMatchedCount(source.id)) { programmes, scores, matched ->
                val byId = channels.associateBy { it.id }
                val films = programmes.filter { Films.candidate(it.programme) }.mapNotNull { p ->
                    byId[p.channelId]?.let { FilmShowing(it, p.programme, scores[Films.key(p.programme)]) }
                }
                FilmCatalogue(source.id, loaded = true, hasEpg = matched > 0, films = films)
            }
        }).onStart { emit(FilmCatalogue(sourceId = source?.id)) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), FilmCatalogue())

    init {
        scope.launch(Dispatchers.IO) {
            combine(database.sourceDao().observeActive(), preferences.refresh, epg.states, futureWindows.pending) { source, revision, _, request ->
                Triple(source, revision, request)
            }
                .collectLatest { (source, revision, request) ->
                    if (request != null && !request.matches(source?.id, revision)) {
                        futureWindows.complete(request)
                        return@collectLatest
                    }
                    val key = preferences.key.value
                    if (source == null || key.isBlank()) return@collectLatest
                    while (true) {
                        val quotaDelay = preferences.quotaDelay(System.currentTimeMillis())
                        if (quotaDelay > 0) { delay(quotaDelay); continue }
                        var retry = false
                        var hasFutureCandidates = false
                        mutableEnriching.value = true
                        try {
                            val now = System.currentTimeMillis()
                            val channels = database.epgDao().filmChannels(source.id).first()
                            val byId = channels.associateBy { it.id }
                            val candidates = enrichmentCandidates(database.epgDao()
                                .filmWindow(source.id, Films.archiveWindowStart(channels, now), now + 2 * Films.DAY).first().mapNotNull { p ->
                                    byId[p.channelId]?.let { FilmShowing(it, p.programme, null) }
                                }, now, futureRequested = request != null)
                            hasFutureCandidates = candidates.any { Films.inWindow(it, now, 1) }
                            retry = enrichFilms(candidates, key, lookup, {
                                database.sourceDao().active()?.id == source.id && preferences.key.value == key
                            })
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            preferences.error("INTERNAL", System.currentTimeMillis())
                            retry = true
                        } finally { mutableEnriching.value = false }
                        if (request != null && futureWindowComplete(retry, lookup.lastError, hasFutureCandidates,
                                epg.states.value[source.id] == EpgState.Running)) {
                            futureWindows.complete(request)
                            return@collectLatest
                        }
                        delay(preferences.quotaDelay(System.currentTimeMillis()).takeIf { it > 0 }
                            ?: if (retry) 60_000L else 60 * 60_000L)
                    }
                }
        }
    }
}
