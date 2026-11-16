package dev.firetvlite.iptv

import android.app.Application
import android.app.Activity
import android.os.Bundle
import android.os.StrictMode
import android.os.SystemClock
import java.io.File
import dev.firetvlite.iptv.data.ClockPreferences
import dev.firetvlite.iptv.data.LoudnessPreferences
import dev.firetvlite.iptv.playback.LoudnessLeveller
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
import dev.firetvlite.iptv.playback.StallHistory
import dev.firetvlite.iptv.update.LaunchUpdateCheck
import dev.firetvlite.iptv.update.UpdateRepository
import dev.firetvlite.iptv.update.ReleaseParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Creates and holds the app-wide objects (manual dependency injection, no code generation).
 * Everything here lives as long as the process.
 */
class AppContainer(application: Application, startedAt: Long = SystemClock.elapsedRealtime()) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val startup = StartupRecorder(File(application.filesDir, "startup-history.json"), applicationScope,
        startedAt, SystemClock::elapsedRealtime) { errorReports }

    val database: FireLiteDatabase = FireLiteDatabase.create(application)
    val epg = EpgRepository(database, applicationScope)
    val films by lazy { FilmRepository(database, OmdbPreferences(application), applicationScope, epg) }
    val clockPreferences by lazy { ClockPreferences(application) }
    val reminderPreferences by lazy { ReminderPreferences(application) }
    internal val loudnessPreferences = LoudnessPreferences(application, applicationScope)
    internal val loudness = LoudnessLeveller()

    val library = LibraryRepository(
        database = database,
        importer = PlaylistImporter(database, application.cacheDir),
        context = application,
        scope = applicationScope,
        epg = epg,
        awaitStartup = startup::awaitReady,
    )

    internal val errorReports: ErrorReporting = ErrorReporting(application, database.reportDao(), applicationScope,
        allowed = ::reportUploadsAllowed, awaitStartup = startup::awaitReady)
    internal val deadChannels: DeadChannels = DeadChannels(File(application.filesDir, "dead-channels.json"), applicationScope,
        reports = errorReports, awaitStartup = startup::awaitReady)
    internal val stalls = StallHistory(File(application.filesDir, "playback-stalls.json"), applicationScope, startup::awaitReady)
    /** The one object that may open a stream. */
    val playback: PlaybackController = PlaybackController(application, applicationScope, deadChannels, stalls, errorReports,
        loudness = loudness, launchEvent = startup::mark)

    private fun reportUploadsAllowed(): Boolean = playback.reportUploadsAllowed
    internal val launchUpdate = LaunchUpdateCheck(applicationScope, playback.firstFrame, BuildConfig.VERSION_CODE.toLong(),
        streamActive = { playback.hasActiveStream }, awaitStartup = startup::awaitReady) {
        if (BuildConfig.UPDATE_REPOSITORY.isBlank()) ReleaseParser.Result.NoApk
        else UpdateRepository(BuildConfig.UPDATE_REPOSITORY, BuildConfig.VERSION_CODE_OFFSET).fetchLatestBackground()
    }

    private val foregroundEpg = ForegroundRefresh(applicationScope) {
        startup.awaitReady()
        database.sourceDao().active()?.let { epg.refresh(it.id) }
    }

    init {
        applicationScope.launch {
            startup.awaitCompletion()
            if (startup.latest.value.total != null) delay(1_500)
            withContext(Dispatchers.IO) {
                clockPreferences
                reminderPreferences
                films
            }
            startup.backgroundReady()
        }
        applicationScope.launch { loudnessPreferences.enabled.collect { loudness.setEnabled(it) } }
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
        val startedAt = SystemClock.elapsedRealtime()
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
        container = AppContainer(this, startedAt)
        val connectedReports = CrashReportStore(File(filesDir, "last-crash.txt"), container.errorReports)
        Thread.setDefaultUncaughtExceptionHandler(connectedReports.handler(
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", previousHandler))
        AppHealthMonitor(this, connectedReports) { container.startup.ready.value }
            .start("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    }
}
