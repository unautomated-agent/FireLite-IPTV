package dev.firetvlite.iptv

import android.app.Application
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.playlist.PlaylistImporter
import dev.firetvlite.iptv.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Creates and holds the app-wide objects (manual dependency injection, no code generation).
 * Everything here lives as long as the process.
 */
class AppContainer(application: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: FireLiteDatabase = FireLiteDatabase.create(application)

    val library = LibraryRepository(
        database = database,
        importer = PlaylistImporter(database, application.cacheDir),
        context = application,
        scope = applicationScope,
    )

    /** The one object that may open a stream. */
    val playback = PlaybackController(application, applicationScope)
}

class FireLiteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
