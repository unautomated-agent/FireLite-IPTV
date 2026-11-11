package dev.firetvlite.iptv.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.dp
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.data.film.rankedFilms
import dev.firetvlite.iptv.data.library.GroupKey
import dev.firetvlite.iptv.data.library.ListZapContext
import dev.firetvlite.iptv.ui.components.ChannelLogo
import dev.firetvlite.iptv.ui.components.FLText
import dev.firetvlite.iptv.ui.components.FocusSurface
import dev.firetvlite.iptv.ui.components.PrimaryButton
import dev.firetvlite.iptv.ui.components.ProgressBar
import dev.firetvlite.iptv.ui.components.clockTime
import dev.firetvlite.iptv.ui.components.minuteClock
import dev.firetvlite.iptv.ui.components.requestFocusWhenReady
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import dev.firetvlite.iptv.ui.viewmodel.LibraryViewModel
import dev.firetvlite.iptv.ui.viewmodel.PlayerViewModel
import java.util.Locale

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun FilmRankingsScreen(library: LibraryViewModel, player: PlayerViewModel, active: Boolean, onSettings: () -> Unit) {
    val key by library.films.preferences.key.collectAsState()
    val films by library.films.showings.collectAsState()
    val source by library.source.collectAsState()
    val now by minuteClock()
    val context = LocalContext.current
    val inputMode = LocalInputModeManager.current
    var tab by remember { mutableStateOf(0) }
    val tabFocus = remember { List(2) { FocusRequester() } }
    val settingsFocus = remember { FocusRequester() }
    val states = remember { List(2) { LazyListState() } }
    val rows = remember(films, tab, now) { rankedFilms(films, now, tab) }
    val colors = FireLiteTheme.colors
    LaunchedEffect(active, key.isNotBlank()) {
        if (active) {
            inputMode.requestInputMode(InputMode.Keyboard)
            (if (key.isBlank()) settingsFocus else tabFocus[tab]).requestFocusWhenReady()
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FLText("Najlepsze filmy", style = FireLiteTheme.typography.display)
        if (key.isBlank()) {
            FLText("Ustaw klucz OMDb w Ustawieniach, aby zobaczyć oceny IMDb i ranking filmów.", style = FireLiteTheme.typography.body)
            FLText("Darmowy klucz: omdbapi.com/apikey.aspx", style = FireLiteTheme.typography.label)
            PrimaryButton("Ustawienia", onSettings, Modifier.focusRequester(settingsFocus))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf("Najbliższe 24 h", "Pojutrze (24–48 h)").forEachIndexed { index, title ->
                    PrimaryButton((if (tab == index) "● " else "") + title, { tab = index },
                        Modifier.focusRequester(tabFocus[index]))
                }
            }
            if (rows.isEmpty()) FLText("Brak filmów w tym przedziale. Odśwież EPG w Ustawieniach.", style = FireLiteTheme.typography.body)
            LazyColumn(state = states[tab], verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(rows, key = { it.identity }) { film ->
                    val p = film.programme
                    val score = film.rating?.imdbRating
                    val airing = p.start <= now && p.stop > now
                    FocusSurface(onClick = {
                        val currentTime = System.currentTimeMillis()
                        if (p.start <= currentTime && p.stop > currentTime && film.channel.sourceId == source?.id) {
                            player.open(ListZapContext(listOf(film.channel)), 0, GroupKey.All)
                        } else Toast.makeText(context, "Zaczyna się o ${clockTime(p.start)}", Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            FLText(score?.let { "IMDb ${String.format(Locale.ROOT, "%.1f", it)}" } ?: "IMDb —",
                                style = FireLiteTheme.typography.title, color = if (score != null) Color(0xFFF5C518) else colors.textSecondary)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                FLText(p.title, style = FireLiteTheme.typography.title,
                                    color = if (score == null) colors.textSecondary else colors.textPrimary, maxLines = 1)
                                FLText(listOfNotNull(p.originalTitle, p.year?.toString()).joinToString(" · "),
                                    style = FireLiteTheme.typography.label, color = colors.textSecondary, maxLines = 1)
                                FLText((if (airing) "NA ŻYWO · " else "") + Films.time(p.start),
                                    style = FireLiteTheme.typography.label, color = colors.accent)
                                if (airing) ProgressBar(((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f))
                            }
                            ChannelLogo(film.channel.name, film.channel.logoUrl, size = 44.dp)
                            FLText(film.channel.name, style = FireLiteTheme.typography.label, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
