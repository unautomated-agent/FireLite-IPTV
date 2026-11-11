package dev.firetvlite.iptv.data.playlist

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class XtreamAddress(val origin: String, val user: String, val password: String,
    val streamId: String? = null, val extension: String = "ts") {
    fun epgUrl(): String = "$origin/xmltv.php?username=${encode(user)}&password=${encode(password)}"
    fun apiUrl(liveStreams: Boolean = false): String =
        "$origin/player_api.php?username=${encode(user)}&password=${encode(password)}" +
            if (liveStreams) "&action=get_live_streams" else ""

    fun accountKey(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(epgUrl().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        private fun uri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
            it.scheme?.lowercase() in setOf("http", "https") && it.host != null && it.userInfo == null
        }

        fun playlist(value: String): XtreamAddress? = runCatching {
            val uri = uri(value) ?: return null
            if (!uri.path.substringAfterLast('/').equals("get.php", true)) return null
            val query = uri.rawQuery.orEmpty().split('&').associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8").lowercase() to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val user = query["username"]?.takeIf { it.isNotEmpty() } ?: return null
            val password = query["password"]?.takeIf { it.isNotEmpty() } ?: return null
            XtreamAddress("${uri.scheme}://${uri.rawAuthority}", user, password)
        }.getOrNull()

        fun stream(value: String): XtreamAddress? = runCatching {
            val uri = uri(value) ?: return null
            val parts = uri.rawPath.trim('/').split('/').let {
                if (it.size == 4 && it[0].equals("live", true)) it.drop(1) else it
            }
            if (parts.size != 3 || !parts[2].matches(Regex("\\d+(?:\\.(?:ts|m3u8))?", RegexOption.IGNORE_CASE))) return null
            if (parts[0].isEmpty() || parts[1].isEmpty()) return null
            XtreamAddress("${uri.scheme}://${uri.rawAuthority}", decode(parts[0]), decode(parts[1]),
                parts[2].substringBefore('.'), parts[2].substringAfter('.', "ts").lowercase())
        }.getOrNull()
    }
}
