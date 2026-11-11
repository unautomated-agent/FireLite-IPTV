package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.ui.theme.FireLiteTheme
import java.util.Locale

val LocalFilmRatings = staticCompositionLocalOf<Map<String, FilmRatingEntity>> { emptyMap() }

@Composable
fun imdbSuffix(programme: ProgrammeEntity?): String {
    if (programme == null || !Films.candidate(programme)) return ""
    val rating = LocalFilmRatings.current[Films.key(programme)]?.imdbRating ?: return ""
    return " · " + stringResource(R.string.imdb_rating, String.format(Locale.ROOT, "%.1f", rating))
}

@Composable
fun ImdbRating(programme: ProgrammeEntity?) {
    val text = imdbSuffix(programme).removePrefix(" · ")
    if (text.isNotEmpty()) FLText(text, style = FireLiteTheme.typography.label, color = Color(0xFFF5C518), maxLines = 1)
}
