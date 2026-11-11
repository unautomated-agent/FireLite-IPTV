package dev.firetvlite.iptv.data.epg

internal enum class EpgRefreshAction { Clear, Skip, Import }

internal object EpgRefreshPolicy {
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    fun action(hasUrls: Boolean, refreshedAt: Long, now: Long, force: Boolean): EpgRefreshAction = when {
        !hasUrls -> EpgRefreshAction.Clear
        !force && refreshedAt > 0 && now - refreshedAt <= MAX_AGE_MS -> EpgRefreshAction.Skip
        else -> EpgRefreshAction.Import
    }
}
