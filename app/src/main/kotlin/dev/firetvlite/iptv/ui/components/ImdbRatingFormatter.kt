package dev.firetvlite.iptv.ui.components

import dev.firetvlite.iptv.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

internal class ImdbRatingFormatter(private val text: (Int, Array<out Any>) -> String) {
    fun format(rating: Double?, votes: Long = 0, showVotes: Boolean = false): String {
        if (rating == null || !rating.isFinite() || rating !in 0.0..10.0) return ""
        val score = text(R.string.imdb_rating, arrayOf(String.format(Locale("pl", "PL"), "%.1f", rating)))
        if (!showVotes || votes <= 0) return score
        val (resource, count) = when {
            votes >= 999_950 -> R.string.imdb_votes_millions to compact(votes, 1_000_000)
            votes >= 1_000 -> R.string.imdb_votes_thousands to compact(votes, 1_000)
            votes == 1L -> R.string.imdb_votes_one to votes.toString()
            votes % 10 in 2..4 && votes % 100 !in 12..14 -> R.string.imdb_votes_few to votes.toString()
            else -> R.string.imdb_votes_many to votes.toString()
        }
        return text(R.string.imdb_rating_with_votes, arrayOf(score, text(resource, arrayOf(count))))
    }

    private fun compact(votes: Long, divisor: Long): String = BigDecimal.valueOf(votes)
        .divide(BigDecimal.valueOf(divisor), 1, RoundingMode.HALF_UP)
        .stripTrailingZeros().toPlainString().replace('.', ',')
}
