package dev.firetvlite.iptv.data.library

import java.util.Locale

object ChannelSearch {
    const val LIMIT = 200
    const val NAME_SQL = "lower(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(c.name, 'Ą', 'A'), 'Ć', 'C'), 'Ę', 'E'), 'Ł', 'L'), 'Ń', 'N'), 'Ó', 'O'), 'Ś', 'S'), 'Ź', 'Z'), 'Ż', 'Z'), 'ą', 'a'), 'ć', 'c'), 'ę', 'e'), 'ł', 'l'), 'ń', 'n'), 'ó', 'o'), 'ś', 's'), 'ź', 'z'), 'ż', 'z'))"

    fun pattern(query: String): String? {
        val trimmed = query.trim().lowercase(Locale.ROOT)
        if (trimmed.isEmpty()) return null
        val normalized = buildString {
            for (char in trimmed) append(when (char) {
                'ą' -> 'a'; 'ć' -> 'c'; 'ę' -> 'e'; 'ł' -> 'l'; 'ń' -> 'n'
                'ó' -> 'o'; 'ś' -> 's'; 'ź', 'ż' -> 'z'
                else -> char
            })
        }
        return "%" + normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
    }
}
