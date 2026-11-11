package dev.firetvlite.iptv

import android.app.Application
import android.app.Activity
import android.os.Bundle
import java.io.File
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.epg.EpgRepository
import dev.firetvlite.iptv.data.epg.ForegroundRefresh
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
    val epg = EpgRepository(database, applicationScope)

    val library = LibraryRepository(
        database = database,
        importer = PlaylistImporter(database, application.cacheDir),
        context = application,
        scope = applicationScope,
        epg = epg,
    )

    /** The one object that may open a stream. */
    val playback = PlaybackController(application, applicationScope)

    private val foregroundEpg = ForegroundRefresh(applicationScope) {
        database.sourceDao().active()?.let { epg.refresh(it.id) }
    }

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (++started == 1) foregroundEpg.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) foregroundEpg.setForeground(false)
            }

            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

class FireLiteApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val reports = CrashReportStore(File(filesDir, "last-crash.txt"))
        Thread.setDefaultUncaughtExceptionHandler(
            reports.handler(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                Thread.getDefaultUncaughtExceptionHandler(),
            ),
        )
        container = AppContainer(this)
    }
}
