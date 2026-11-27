package dev.firetvlite.iptv.update

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateFilesTest {
    private fun files() = UpdateFiles(Files.createTempDirectory("updates").toFile())

    @Test fun staleApkIsRemovedWhenNoDownloadWasRequested() {
        val files = files()
        files.apk.writeText("old")
        files.removeStale()
        assertFalse(files.apk.exists())
    }

    @Test fun lateCleanupKeepsADownloadInProgressAndItsApk() {
        val files = files()
        files.claim()
        val partial = java.io.File(files.apk.path + ".part").apply { writeText("half") }
        files.removeStale()
        assertTrue(partial.exists())
        partial.renameTo(files.apk)
        files.removeStale()
        assertTrue(files.apk.exists())
    }
}
