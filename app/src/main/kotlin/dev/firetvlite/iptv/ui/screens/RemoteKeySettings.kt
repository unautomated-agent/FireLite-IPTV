package dev.firetvlite.iptv.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.playback.RemoteAlias
import dev.firetvlite.iptv.playback.RemoteLearnResult
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.SecondaryButton
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import kotlinx.coroutines.delay

/** Lets a button of another remote, such as a TV remote, act like Fire TV's Menu or Home. */
@Composable
internal fun RemoteKeySettings() {
    val remote = (LocalContext.current.applicationContext as FireLiteApplication).container.remoteKeys
    val keys by remote.keys.collectAsState()
    val learning by remote.learning.collectAsState()
    val result by remote.result.collectAsState()
    LaunchedEffect(learning) {
        if (learning != null) {
            delay(10_000)
            remote.timeout()
        }
    }
    FLText(stringResource(R.string.remote_help), style = FireLiteTheme.typography.body)
    listOf(RemoteAlias.Menu to R.string.remote_menu, RemoteAlias.Home to R.string.remote_home).forEach { (alias, label) ->
        val state = when {
            learning == alias -> stringResource(R.string.remote_waiting)
            keys.key(alias) != 0 -> stringResource(R.string.remote_set)
            else -> stringResource(R.string.remote_none)
        }
        SecondaryButton("${stringResource(label)}: $state", { remote.startLearning(alias) },
            Modifier.settingsControl("remote-${alias.name}"))
        result?.takeIf { it.first == alias && it.second != RemoteLearnResult.Cancelled }?.let { (_, outcome) ->
            FLText(stringResource(when (outcome) {
                RemoteLearnResult.Saved -> R.string.remote_saved
                RemoteLearnResult.Used -> R.string.remote_used
                else -> R.string.remote_timeout
            }), style = FireLiteTheme.typography.body)
        }
    }
    if (keys.menu != 0 || keys.home != 0) SecondaryButton(stringResource(R.string.remote_clear), {
        remote.clear(RemoteAlias.Menu)
        remote.clear(RemoteAlias.Home)
    }, Modifier.settingsControl("remote-clear"))
}
