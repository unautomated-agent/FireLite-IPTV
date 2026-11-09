package dev.firetvlite.iptv.update

/** A published build: the release tag is `build-<N>` and the installed versionCode is offset + N. */
data class ReleaseInfo(
    val tag: String,
    val buildNumber: Int,
    val versionCode: Long,
    val apkUrl: String,
    val apkSizeBytes: Long,
)

/** What is installed right now. */
data class InstalledVersion(
    val versionName: String,
    val versionCode: Long,
)

enum class FailureReason {
    NotConfigured,
    Network,
    NoApk,
    UnknownTag,
    Download,
    PermissionNeeded,
    InstallerUnavailable,
}

/** The whole life cycle of an update, as seen by the UI. */
sealed interface UpdateState {
    /** Nothing has been checked yet. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data object UpToDate : UpdateState

    /** A newer build exists and can be installed. */
    data class Available(val release: ReleaseInfo) : UpdateState

    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState

    /** [release] is set when a newer build is known, so the user can simply retry. */
    data class Failed(val reason: FailureReason, val release: ReleaseInfo? = null) : UpdateState
}

/** The newer build this state is about, if there is one. */
val UpdateState.pendingRelease: ReleaseInfo?
    get() = when (this) {
        is UpdateState.Available -> release
        is UpdateState.Downloading -> release
        is UpdateState.Failed -> release
        else -> null
    }
