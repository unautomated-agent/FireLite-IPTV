package dev.firetvlite.iptv.data.library

internal object SourcePolicy {
    private const val STALE_AFTER_MS = 24L * 60 * 60 * 1000

    fun needsImport(refreshedAt: Long, channelCount: Int, now: Long): Boolean =
        channelCount == 0 || refreshedAt == 0L || now - refreshedAt > STALE_AFTER_MS

    fun nextUsedAt(previous: Long, now: Long): Long = maxOf(previous + 1, now)

    fun needsReimport(importedVersion: Int, importedRevision: Int, version: Int, revision: Int): Boolean =
        importedVersion != version || importedRevision != revision
}
