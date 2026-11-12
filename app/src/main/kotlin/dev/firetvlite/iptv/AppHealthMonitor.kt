package dev.firetvlite.iptv

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

internal class AppHealthMonitor(private val application: Application, private val reports: CrashReportStore) {
    @Volatile private var foreground = false

    fun start(version: String) {
        val main = Looper.getMainLooper()
        val handler = Handler(main)
        val watchdog = MainThreadWatchdog(
            post = { handler.post(it) },
            stack = { main.thread.stackTrace.take(32).joinToString("\n") },
            report = { duration, stack -> reports.stall(version, duration, stack) },
        )
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { foreground = ++started > 0 }
            override fun onActivityStopped(activity: Activity) { foreground = --started > 0 }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Thread({
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val manager = application.getSystemService(ActivityManager::class.java)
                reports.processExits(manager.getHistoricalProcessExitReasons(application.packageName, 0, 4).map { exit ->
                    ProcessExitRecord(exit.timestamp, reason(exit.reason), exit.status,
                        exit.description.orEmpty(), exit.pss, exit.rss)
                })
            }
            while (!Thread.currentThread().isInterrupted) {
                runCatching { watchdog.tick(SystemClock.uptimeMillis(), foreground) }
                try { Thread.sleep(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }, "FireLite-health").apply { isDaemon = true; start() }
    }

    private fun reason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        else -> "OTHER($reason)"
    }
}
