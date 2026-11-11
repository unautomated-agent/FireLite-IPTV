package dev.firetvlite.iptv.data.library

import android.content.Context
import dev.firetvlite.iptv.BuildConfig
import androidx.room.withTransaction
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.epg.EpgRepository
import dev.firetvlite.iptv.data.playlist.EmptyPlaylistException
import dev.firetvlite.iptv.data.playlist.PlaylistImporter
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ImportFailure { Network, EmptyPlaylist, NoSource }

sealed interface ImportState {
    data object Idle : ImportState

    data object Running : ImportState

    data class Done(val channelCount: Int) : ImportState

    data class Failed(val reason: ImportFailure) : ImportState
}

/**
 * Owns saved playlists, the active source and its refresh lifecycle.
 *
 * - After the first start the playlist address of FireLite 1.x is taken over, so nothing has to be typed again.
 * - The list is reloaded on start if it is empty or older than a day, and on request.
 */
class LibraryRepository(
    private val database: FireLiteDatabase,
    private val importer: PlaylistImporter,
    private val context: Context,
    private val scope: CoroutineScope,
    private val epg: EpgRepository,
) {
    private val sourceDao = database.sourceDao()
    private val operations = Mutex()
    private var importJob: Job? = null
    val source: Flow<SourceEntity?> = sourceDao.observeActive()
    val sources = sourceDao.observeAll()

    private val mutableImportState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = mutableImportState.asStateFlow()

    init {
        scope.launch {
            operations.withLock {
                adoptLegacySettings()
                loadActive()
            }
        }
    }

    /** Adds and activates a playlist, or explicitly edits an existing one. */
    fun saveSource(url: String, userAgent: String, epgUrl: String, id: Long? = null) {
        scope.launch {
            operations.withLock {
                cancelImport()
                database.withTransaction {
                    val savedId = if (id == null) {
                        sourceDao.insert(SourceEntity(name = DEFAULT_NAME, url = url, userAgent = userAgent, epgUrl = epgUrl))
                    } else {
                        if (sourceDao.byId(id) == null) return@withTransaction
                        epg.remove(id)
                        sourceDao.updateConnection(id, url, userAgent, epgUrl)
                        id
                    }
                    sourceDao.activate(savedId, System.currentTimeMillis())
                }
                loadActive(force = true)
            }
        }
    }

    fun activateSource(id: Long) {
        scope.launch {
            operations.withLock {
                if (sourceDao.byId(id) == null) return@withLock
                cancelImport()
                sourceDao.activate(id, System.currentTimeMillis())
                loadActive()
            }
        }
    }

    fun deleteSource(id: Long) {
        scope.launch {
            operations.withLock {
                val wasActive = sourceDao.active()?.id == id
                if (wasActive) cancelImport()
                epg.remove(id)
                sourceDao.delete(id)
                if (wasActive) loadActive()
            }
        }
    }

    fun refresh() {
        scope.launch {
            operations.withLock {
                cancelImport()
                loadActive(force = true)
            }
        }
    }

    private fun cancelImport() {
        importJob?.cancel()
        mutableImportState.value = ImportState.Idle
    }

    private suspend fun loadActive(force: Boolean = false) {
        val source = sourceDao.active()
        if (source == null) {
            if (force) mutableImportState.value = ImportState.Failed(ImportFailure.NoSource)
            return
        }
        if (!force && !SourcePolicy.needsImport(
                source.refreshedAt, database.channelDao().count(source.id), System.currentTimeMillis(),
            )
            && !SourcePolicy.needsReimport(source.importedVersionCode, source.importedParserRevision,
                BuildConfig.VERSION_CODE, PlaylistImporter.PARSER_REVISION)
        ) {
            epg.refresh(source.id)
            return
        }
        importJob = scope.launch {
            mutableImportState.value = ImportState.Running
            val result = try {
                ImportState.Done(importer.import(source))
            } catch (e: EmptyPlaylistException) {
                ImportState.Failed(ImportFailure.EmptyPlaylist)
            } catch (e: IOException) {
                ImportState.Failed(ImportFailure.Network)
            }
            currentCoroutineContext().ensureActive()
            mutableImportState.value = result
            epg.refresh(source.id, force = result is ImportState.Done)
        }
    }

    /** FireLite 1.x kept its playlist address in the preferences file "firelite". */
    private suspend fun adoptLegacySettings() {
        val legacy = context.getSharedPreferences("firelite", Context.MODE_PRIVATE)
        if (legacy.getBoolean("playlist_history_initialized", false)) return
        if (sourceDao.active() == null) {
            val url = legacy.getString("url", "").orEmpty()
            if (url.isNotEmpty()) {
                val agent = legacy.getString("ua", "").orEmpty().ifEmpty { DEFAULT_USER_AGENT }
                sourceDao.insert(SourceEntity(
                    name = DEFAULT_NAME, url = url, userAgent = agent, lastUsedAt = System.currentTimeMillis(),
                ))
            }
        }
        legacy.edit().putBoolean("playlist_history_initialized", true).apply()
    }

    companion object {
        const val DEFAULT_USER_AGENT = "FireLite/2.0"
        private const val DEFAULT_NAME = "M3U"
    }
}
