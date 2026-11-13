package dev.firetvlite.iptv.data.film

import dev.firetvlite.iptv.data.db.ProgrammeEntity

enum class OmdbMissType(val label: String) {
    PART("część"), DOCUMENTARY("dokument"), ANIMATION("animacja/dzieci"), TV_MOVIE("film TV"),
    OLD("przed 1970"), NO_ORIGINAL("bez oryginalnego"), OTHER("inne"),
}

object OmdbMissAnalysis {
    private val nonFilms = setOf("sport", "sports", "koncert", "concert", "teatr", "theatre", "theater",
        "spektakl teatralny", "theatre performance", "documentary series", "serial dokumentalny",
        "magazyn sportowy", "transmisja sportowa")
    private val part = Regex("(?iu)\\b(cz\\.?|część|part)\\s*[0-9ivxl]+\\b")
    private val documentary = Regex("(?iu)\\b(dokument\\w*|documentary)\\b")
    private val animation = Regex("(?iu)animac|animowan|animated|children|dla dzieci")
    private val television = Regex("(?iu)telewizyjn|tv movie|television film")

    fun categories(p: ProgrammeEntity): List<String> = p.categories.ifBlank { p.category.orEmpty() }.split('\n')
        .map { it.substringBefore("\",]\"name\":\"").trim() }

    fun skipReason(p: ProgrammeEntity): String? = when {
        categories(p).any { Films.normalized(it) in nonFilms } -> "NON_FILM_CATEGORY"
        listOf(p.title, p.originalTitle.orEmpty()).all { Films.normalized(it).isEmpty() } -> "NO_TITLE"
        else -> null
    }

    fun features(p: ProgrammeEntity): Set<OmdbMissType> = buildSet {
        val category = categories(p).joinToString("\n")
        if (part.containsMatchIn(p.title + "\n" + p.originalTitle.orEmpty())) add(OmdbMissType.PART)
        if (documentary.containsMatchIn(category)) add(OmdbMissType.DOCUMENTARY)
        if (animation.containsMatchIn(category)) add(OmdbMissType.ANIMATION)
        if (television.containsMatchIn(category)) add(OmdbMissType.TV_MOVIE)
        if (p.year != null && p.year < 1970) add(OmdbMissType.OLD)
        if (p.originalTitle.isNullOrBlank()) add(OmdbMissType.NO_ORIGINAL)
        if (isEmpty()) add(OmdbMissType.OTHER)
    }

    fun primary(p: ProgrammeEntity): OmdbMissType = features(p).minBy { it.ordinal }
}
