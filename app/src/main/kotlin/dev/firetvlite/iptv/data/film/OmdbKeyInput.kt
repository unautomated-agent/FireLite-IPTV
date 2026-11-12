package dev.firetvlite.iptv.data.film

fun normalizeOmdbKey(input: String): String? {
    val value = input.trim()
    if (Regex("\\bVERIFYKEY\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(value)) return null
    return Regex("\\bapikey\\s*=\\s*([^\\s&#\"'<>]*)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.get(1) ?: value
}
