package dev.firetvlite.iptv.ui.screens

import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.ui.components.ImdbRatingFormatter
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ImdbRatingFormatterTest {
    private val names = mapOf(
        R.string.imdb_rating to "imdb_rating",
        R.string.imdb_rating_with_votes to "imdb_rating_with_votes",
        R.string.imdb_votes_one to "imdb_votes_one",
        R.string.imdb_votes_few to "imdb_votes_few",
        R.string.imdb_votes_many to "imdb_votes_many",
        R.string.imdb_votes_thousands to "imdb_votes_thousands",
        R.string.imdb_votes_millions to "imdb_votes_millions",
    )
    private val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(File("src/main/res/values/strings.xml")).getElementsByTagName("string").let { nodes ->
            (0 until nodes.length).associate { index ->
                val node = nodes.item(index)
                node.attributes.getNamedItem("name").nodeValue to node.textContent
            }
        }
    private val formatter = ImdbRatingFormatter { id, args ->
        String.format(Locale.ROOT, strings.getValue(names.getValue(id)), *args)
    }

    @Test fun compactRatingAlwaysIncludesScaleAndPolishDecimalComma() {
        assertEquals("IMDb 7,8/10", formatter.format(7.8, 12_000))
        assertEquals("IMDb 0,0/10", formatter.format(0.0))
        assertEquals("IMDb 10,0/10", formatter.format(10.0))
        assertEquals("IMDb 7,9/10", formatter.format(7.85))
    }

    @Test fun decimalCommaDoesNotDependOnDeviceLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("IMDb 7,8/10 · 12,3 tys. głosów", formatter.format(7.8, 12_345, true))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun missingAndInvalidRatingsNeverProducePartialBadges() {
        for (rating in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 10.1)) {
            assertEquals("", formatter.format(rating, 12_000, true))
        }
    }

    @Test fun missingOrInvalidVoteCountKeepsTheScore() {
        assertEquals("IMDb 7,8/10", formatter.format(7.8, 0, true))
        assertEquals("IMDb 7,8/10", formatter.format(7.8, -1, true))
    }

    @Test fun smallVoteCountsUsePolishForms() {
        for ((votes, expected) in mapOf(1L to "1 głos", 2L to "2 głosy", 4L to "4 głosy", 5L to "5 głosów",
                12L to "12 głosów", 14L to "14 głosów", 21L to "21 głosów", 22L to "22 głosy",
                112L to "112 głosów", 114L to "114 głosów", 122L to "122 głosy", 999L to "999 głosów")) {
            assertEquals("IMDb 7,8/10 · $expected", formatter.format(7.8, votes, true))
        }
    }

    @Test fun largeVoteCountsAreCompactAndAvoidRedundantDecimals() {
        for ((votes, expected) in mapOf(1_000L to "1 tys. głosów", 12_000L to "12 tys. głosów",
                12_350L to "12,4 tys. głosów", 999_949L to "999,9 tys. głosów",
                999_950L to "1 mln głosów", 1_000_000L to "1 mln głosów", 1_250_000L to "1,3 mln głosów")) {
            assertEquals("IMDb 7,8/10 · $expected", formatter.format(7.8, votes, true))
        }
        assertEquals("IMDb 7,8/10 · 9223372036854,8 mln głosów", formatter.format(7.8, Long.MAX_VALUE, true))
    }
}
