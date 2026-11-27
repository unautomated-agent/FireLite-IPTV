package dev.firetvlite.iptv.update

import java.io.File

/**
 * The folder an update is downloaded into. The APK of the previous update is removed some time after start,
 * but never once a download was requested: that removal used to delete the APK that had just been downloaded.
 */
class UpdateFiles(private val dir: File) {
    val apk = File(dir, "firelite.apk")
    private var claimed = false

    /** Call before downloading; from then on [removeStale] leaves the folder alone. */
    @Synchronized fun claim() { claimed = true }

    @Synchronized fun removeStale() { if (!claimed) dir.deleteRecursively() }
}
