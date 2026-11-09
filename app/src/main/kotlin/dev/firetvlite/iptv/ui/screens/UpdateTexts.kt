package dev.firetvlite.iptv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.update.FailureReason
import dev.firetvlite.iptv.update.UpdateState

/** One-line, user-facing description of an [UpdateState], in Polish. Empty while nothing happened yet. */
@Composable
internal fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> ""
    UpdateState.Checking -> stringResource(R.string.update_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_available, state.release.buildNumber)
    is UpdateState.Downloading -> {
        val progress = state.progress
        if (progress == null) {
            stringResource(R.string.update_downloading_unknown)
        } else {
            stringResource(R.string.update_downloading, (progress * 100).toInt())
        }
    }
    is UpdateState.Failed -> stringResource(
        when (state.reason) {
            FailureReason.NotConfigured -> R.string.update_error_not_configured
            FailureReason.Network -> R.string.update_error_network
            FailureReason.NoApk -> R.string.update_error_no_apk
            FailureReason.UnknownTag -> R.string.update_error_unknown_tag
            FailureReason.Download -> R.string.update_error_download
            FailureReason.PermissionNeeded -> R.string.update_error_permission
            FailureReason.InstallerUnavailable -> R.string.update_error_installer
        },
    )
}
