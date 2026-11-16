package dev.firetvlite.iptv.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.FireLiteApplication
import dev.firetvlite.iptv.data.film.omdbIssueMessage
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.LabeledTextField
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

@Composable
internal fun OmdbSettings() {
    val preferences = (LocalContext.current.applicationContext as FireLiteApplication).container.films.preferences
    val key by preferences.key.collectAsState()
    val error by preferences.currentError.collectAsState()
    var draft by remember(key) { mutableStateOf(key) }
    var activationLink by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabeledTextField(stringResource(R.string.omdb_key_label), draft, { draft = it; activationLink = false }, secret = true,
            modifier = Modifier.settingsControl("omdb-key"))
        FLText(stringResource(R.string.omdb_key_help), style = FireLiteTheme.typography.body)
        FLText(stringResource(R.string.omdb_key_activation_help), style = FireLiteTheme.typography.body)
        if (activationLink) FLText(stringResource(R.string.omdb_key_activation_link), style = FireLiteTheme.typography.body)
        omdbIssueMessage(error)?.let { message ->
            FLText(stringResource(message, error.orEmpty()), style = FireLiteTheme.typography.body)
        }
        PrimaryButton(stringResource(R.string.omdb_key_save), { activationLink = !preferences.save(draft) }, Modifier.settingsControl("omdb-save"))
    }
}
