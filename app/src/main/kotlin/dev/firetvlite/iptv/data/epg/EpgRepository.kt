package dev.firetvlite.iptv.data.epg

import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.ChannelProgramme
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EpgState {
    data object Running : EpgState
    data class Done(val count: Int) : EpgState
    data class Failed(val detail: String) : EpgState
}

data class CurrentAndNext(val current: ProgrammeEntity?, val next: ProgrammeEntity?)

/** Refreshes are serialized off the main thread. Queries expose only the last published generation. */
@OptIn(ExperimentalCoroutinesApi::class)
class EpgRepository(private val database: FireLiteDatabase, private val scope: CoroutineScope) {
    private val importer = EpgImporter(database)
    private val gate = Mutex()
    private val jobs = HashMap<Long, Job>()
    private val mutableStates = MutableStateFlow<Map<Long, EpgState>>(emptyMap())
    val states = mutableStates.asStateFlow()

    @Synchronized
    fun refresh(sourceId: Long, force: Boolean = false) {
        if (jobs[sourceId]?.isActive == true && !force) return
        jobs[sourceId]?.cancel()
        jobs[sourceId] = scope.launch(Dispatchers.IO) {
            gate.withLock {
                val source = database.sourceDao().byId(sourceId) ?: return@withLock
                try {
                    val urls = EpgSources.urls(source.epgUrl, source.discoveredEpgUrls, source.url)
                    when (EpgRefreshPolicy.action(urls.isNotEmpty(), source.epgRefreshedAt, System.currentTimeMillis(), force)) {
                        EpgRefreshAction.Clear -> {
                            database.withTransaction {
                                val current = database.sourceDao().byId(sourceId) ?: return@withTransaction
                                if (EpgSources.urls(current.epgUrl, current.discoveredEpgUrls, current.url).isNotEmpty()) return@withTransaction
                                database.sourceDao().publishEpg(sourceId, "", 0, 0)
                                database.epgDao().clearMappings(sourceId)
                                database.epgDao().clearProgrammes(sourceId)
                            }
                            mutableStates.update { it - sourceId }
                            return@withLock
                        }
                        EpgRefreshAction.Skip -> return@withLock
                        EpgRefreshAction.Import -> Unit
                    }
                    mutableStates.update { it + (sourceId to EpgState.Running) }
                    val count = importer.import(source, urls)
                    currentCoroutineContext().ensureActive()
                    mutableStates.update { it + (sourceId to EpgState.Done(count)) }
                } catch (e: CancellationException) {
                    mutableStates.update { it - sourceId }
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    mutableStates.update { it + (sourceId to EpgState.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty()}")) }
                }
            }
        }
    }

    @Synchronized
    fun remove(sourceId: Long) {
        jobs.remove(sourceId)?.cancel()
        mutableStates.update { it - sourceId }
    }

    /** Advances with wall-clock time even if the underlying guide does not change. */
    fun observeCurrentAndNext(channelId: Long): Flow<CurrentAndNext> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }.flatMapLatest { now ->
        observeCurrentAndNextAt(channelId, now)
    }

    fun observeCurrentAndNextAt(channelId: Long, time: Long): Flow<CurrentAndNext> =
        combine(database.epgDao().observeAt(channelId, time), database.epgDao().observeNext(channelId, time)) {
                current, next -> CurrentAndNext(current, next)
        }

    /** Half-open overlap window [from, until); results include the owning channel ID. */
    fun programmes(channelIds: List<Long>, from: Long, until: Long): Flow<List<ChannelProgramme>> =
        if (channelIds.isEmpty() || from >= until) flowOf(emptyList())
        else combine(channelIds.distinct().chunked(900).map { database.epgDao().observeWindow(it, from, until) }) { batches ->
            batches.flatMap { it }.sortedWith(compareBy({ it.channelId }, { it.programme.start }))
        }

    suspend fun programmeAt(channelId: Long, time: Long): ProgrammeEntity? =
        database.epgDao().programmeAt(channelId, time)

    fun guideChannels(channelIds: List<Long>): Flow<Set<Long>> =
        if (channelIds.isEmpty()) flowOf(emptySet())
        else combine(channelIds.distinct().chunked(900).map { database.epgDao().observeGuideChannels(it) }) { batches ->
            batches.flatMap { it }.toSet()
        }
}
