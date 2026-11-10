package dev.firetvlite.iptv.data.library

import android.content.Context
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.db.SourceEntity
import dev.firetvlite.iptv.data.playlist.EmptyPlaylistException
import dev.firetvlite.iptv.data.playlist.PlaylistImporter
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ImportFailure { Network, EmptyPlaylist, NoSource }

sealed interface ImportState {
    data object Idle : ImportState

    data object Running : ImportState

    data class Done(val channelCount: Int) : ImportState

    data class Failed(val reason: ImportFailure) : ImportState
}

/**
 * Owns the channel list: which playlist is configured and when it is (re)loaded.
 *
 * - After the first start the playlist address of FireLite 1.x is taken over, so nothing has to be typed again.
 * - The list is reloaded on start if it is empty or older than a day, and on request.
 */
class LibraryRepository(
    private val database: FireLiteDatabase,
    private val importer: PlaylistImporter,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val source: Flow<SourceEntity?> = database.sourceDao().observeFirst()

    private val mutableImportState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = mutableImportState.asStateFlow()

    init {
        scope.launch {
            adoptLegacySettings()
            val source = database.sourceDao().first() ?: return@launch
            val stale = System.currentTimeMillis() - source.refreshedAt > STALE_AFTER_MS
            if (stale || database.channelDao().count(source.id) == 0) runImport(source)
        }
    }

    /** Saves the playlist address (and optional User-Agent) and loads the list. */
    fun saveSource(url: String, userAgent: String) {
        scope.launch {
            val dao = database.sourceDao()
            val existing = dao.first()
            if (existing == null) {
                dao.insert(SourceEntity(name = DEFAULT_NAME, url = url, userAgent = userAgent))
            } else {
                dao.updateConnection(existing.id, url, userAgent)
            }
            dao.first()?.let { runImport(it) }
        }
    }

    fun refresh() {
        scope.launch {
            val source = database.sourceDao().first()
            if (source == null) {
                mutableImportState.value = ImportState.Failed(ImportFailure.NoSource)
            } else {
                runImport(source)
            }
        }
    }

    private suspend fun runImport(source: SourceEntity) {
        if (mutableImportState.value == ImportState.Running) return
        mutableImportState.value = ImportState.Running
        mutableImportState.value = try {
            ImportState.Done(importer.import(source))
        } catch (e: EmptyPlaylistException) {
            ImportState.Failed(ImportFailure.EmptyPlaylist)
        } catch (e: IOException) {
            ImportState.Failed(ImportFailure.Network)
        }
    }

    /** FireLite 1.x kept its playlist address in the preferences file "firelite". */
    private suspend fun adoptLegacySettings() {
        if (database.sourceDao().first() != null) return
        val legacy = context.getSharedPreferences("firelite", Context.MODE_PRIVATE)
        val url = legacy.getString("url", "").orEmpty()
        if (url.isEmpty()) return
        val agent = legacy.getString("ua", "").orEmpty().ifEmpty { DEFAULT_USER_AGENT }
        database.sourceDao().insert(SourceEntity(name = DEFAULT_NAME, url = url, userAgent = agent))
    }

    companion object {
        const val DEFAULT_USER_AGENT = "FireLite/2.0"
        private const val DEFAULT_NAME = "M3U"
        private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000
    }
}
