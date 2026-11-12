package dev.firetvlite.iptv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.firetvlite.iptv.data.db.FilmRatingEntity
import dev.firetvlite.iptv.data.db.ProgrammeEntity
import dev.firetvlite.iptv.data.film.Films
import dev.firetvlite.iptv.ui.theme.FireLiteTheme

val LocalFilmRatings = staticCompositionLocalOf<Map<String, FilmRatingEntity>> { emptyMap() }

@Composable
fun imdbSuffix(programme: ProgrammeEntity?, showVotes: Boolean = false): String {
    if (programme == null || !Films.candidate(programme)) return ""
    val rating = LocalFilmRatings.current[Films.key(programme)] ?: return ""
    val text = imdbRatingText(rating, showVotes)
    return if (text.isEmpty()) "" else " · $text"
}

@Composable
fun imdbRatingText(rating: FilmRatingEntity?, showVotes: Boolean = false): String {
    val context = LocalContext.current
    val formatter = remember(context) { ImdbRatingFormatter { id, args -> context.getString(id, *args) } }
    return formatter.format(rating?.imdbRating, rating?.imdbVotes ?: 0, showVotes)
}

@Composable
fun ImdbRating(programme: ProgrammeEntity?, showVotes: Boolean = false) {
    val text = imdbSuffix(programme, showVotes).removePrefix(" · ")
    if (text.isNotEmpty()) FLText(text, style = FireLiteTheme.typography.label, color = Color(0xFFF5C518), maxLines = 1)
}
