package dev.firetvlite.iptv.data.film

import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.ChannelEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.FilmBudgetEntity
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.epg.EpgRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

data class FilmShowing(val channel: ChannelEntity, val programme: ProgrammeEntity, val rating: FilmRatingEntity?) {
    val identity: String get() = "${channel.id}:${programme.start}"
}

fun rankedFilms(films: List<FilmShowing>, now: Long, tab: Int): List<FilmShowing> = films
    .filter { Films.candidate(it.programme) && Films.inWindow(it.programme, now, tab) }
    .sortedWith(compareByDescending<FilmShowing> { it.rating?.imdbRating ?: -1.0 }
        .thenByDescending { it.rating?.imdbVotes ?: 0 }.thenBy { it.programme.start }.thenBy { it.channel.id })

@OptIn(ExperimentalCoroutinesApi::class)
class FilmRepository(private val database: FireLiteDatabase, val preferences: OmdbPreferences,
    scope: CoroutineScope, epg: EpgRepository) {
    private val dao = database.filmDao()
    private val lookup = FilmLookup(OmdbHttpClient(preferences::baseUrl), object : FilmStore {
        override suspend fun cached(key: String) = dao.cached(key)
        override suspend fun save(rating: FilmRatingEntity) = dao.save(rating)
        override suspend fun reserve(day: Long): Boolean = database.withTransaction {
            dao.budget(FilmBudgetEntity(day))
            dao.reserve(day) == 1
        }
    })
    private val clock = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }
    val ratings = observeFilmRatings(dao.observeRatings(), preferences.key, clock)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val showings = database.sourceDao().observeActive().flatMapLatest { source ->
        if (source == null) flowOf(emptyList()) else clock.flatMapLatest { now ->
            combine(database.epgDao().filmWindow(source.id, now, now + 2 * Films.DAY),
                database.epgDao().filmChannels(source.id), ratings) { programmes, channels, scores ->
                val byId = channels.associateBy { it.id }
                programmes.filter { Films.candidate(it.programme) }.mapNotNull { p ->
                    byId[p.channelId]?.let { FilmShowing(it, p.programme, scores[Films.key(p.programme)]) }
                }
            }
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        scope.launch(Dispatchers.IO) {
            combine(database.sourceDao().observeActive(), preferences.key, epg.states) { source, key, _ -> source to key }
                .collectLatest { (source, key) ->
                    if (source == null || key.isBlank()) return@collectLatest
                    while (true) {
                        try {
                            val now = System.currentTimeMillis()
                            val candidates = database.epgDao().filmWindow(source.id, now, now + 2 * Films.DAY).first()
                                .map { it.programme }.filter { Films.candidate(it) && it.start >= now }
                                .distinctBy(Films::key)
                            for (p in candidates) {
                                currentCoroutineContext().ensureActive()
                                if (database.sourceDao().active()?.id != source.id || preferences.key.value != key) break
                                if (lookup.lookup(p, key, System.currentTimeMillis()) == null) break
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) { /* Retry on the next refresh. */ }
                        delay(60 * 60_000L)
                    }
                }
        }
    }
}
