package dev.firetvlite.iptv

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.StrictMode
import java.io.File
import dev.firetvlite.iptv.data.ClockPreferences
import dev.firetvlite.iptv.data.db.FireLiteDatabase
import dev.firetvlite.iptv.data.epg.EpgRepository
import dev.firetvlite.iptv.data.epg.ForegroundRefresh
import dev.firetvlite.iptv.data.film.FilmRepository
import dev.firetvlite.iptv.data.film.OmdbPreferences
import dev.firetvlite.iptv.data.film.ReminderPreferences
import dev.firetvlite.iptv.data.library.LibraryRepository
import dev.firetvlite.iptv.data.playlist.PlaylistImporter
import dev.firetvlite.iptv.playback.PlaybackController
import dev.firetvlite.iptv.playback.DeadChannels
import dev.firetvlite.iptv.reporting.ErrorReporting
import dev.firetvlite.iptv.reporting.CrashReportHandoff
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
    val films = FilmRepository(database, OmdbPreferences(application), applicationScope, epg)
    val clockPreferences = ClockPreferences(application)
    val reminderPreferences = ReminderPreferences(application)

    val library = LibraryRepository(
        database = database,
        importer = PlaylistImporter(database, application.cacheDir),
        context = application,
        scope = applicationScope,
        epg = epg,
    )

    internal val errorReports: ErrorReporting = ErrorReporting(application, database.reportDao(), applicationScope,
        allowed = ::reportUploadsAllowed)
    internal val deadChannels: DeadChannels = DeadChannels(File(application.filesDir, "dead-channels.json"), applicationScope, reports = errorReports)
    /** The one object that may open a stream. */
    val playback: PlaybackController = PlaybackController(application, applicationScope, deadChannels, errorReports)

    private fun reportUploadsAllowed(): Boolean = playback.reportUploadsAllowed

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
                if (--started == 0) {
                    foregroundEpg.setForeground(false)
                    playback.stopHealthCheck()
                }
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
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build())
        }
        val reports = CrashReportStore(File(filesDir, "last-crash.txt"),
            CrashReportHandoff(File(filesDir, "report-crash-handoff")))
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            reports.handler(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                previousHandler,
            ),
        )
        container = AppContainer(this)
        val connectedReports = CrashReportStore(File(filesDir, "last-crash.txt"), container.errorReports)
        Thread.setDefaultUncaughtExceptionHandler(connectedReports.handler(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", previousHandler))
        AppHealthMonitor(this, connectedReports).start("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
