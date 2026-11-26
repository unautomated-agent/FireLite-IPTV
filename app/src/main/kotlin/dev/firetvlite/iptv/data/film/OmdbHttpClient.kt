package dev.firetvlite.iptv.data.film

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class OmdbHttpClient(private val baseUrl: () -> String = { "https://www.omdbapi.com/" }) : OmdbClient {
    override suspend fun lookup(key: String, title: String, year: Int?): OmdbResult = request(key, movieQuery(title, year), ::decode)

    override suspend fun search(key: String, title: String, year: Int, tolerance: Int, second: suspend () -> Boolean): OmdbResult {
        var id: String? = null
        val found = request(key, searchQuery(title)) { body ->
            (decode(body, requireYear = false) as? OmdbResult.Failed) ?: OmdbResult.Missing.also { id = searchMatch(body, year, tolerance) }
        }
        val match = id
        return when {
            found is OmdbResult.Failed -> found
            match == null -> OmdbResult.Missing
            !second() -> OmdbResult.Failed("LOCAL_BUDGET")
            else -> request(key, "i=$match", ::decode)
        }
    }

    private suspend fun request(key: String, parameters: String, decode: (String) -> OmdbResult): OmdbResult = withContext(Dispatchers.IO) {
        try {
            val query = "apikey=${URLEncoder.encode(key, "UTF-8")}&$parameters"
            val connection = URL(baseUrl().trimEnd('/') + "/?" + query).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            try {
                val status = connection.responseCode
                if (status != 200) {
                    val decoded = try { connection.errorStream?.use { decode(readBody(it)) } }
                        catch (_: Exception) { null }
                    if (decoded is OmdbResult.Failed && decoded.code in setOf("AUTH", "REMOTE_LIMIT")) decoded
                    else OmdbResult.Failed("HTTP_$status")
                } else {
                    val body = connection.inputStream.use { readBody(it) }
                    decode(body)
                }
            } finally { connection.disconnect() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OmdbResult.Failed()
        }
    }

    companion object {
        internal fun movieQuery(title: String, year: Int?): String =
            listOfNotNull("t" to title, year?.let { "y" to it.toString() }, "type" to "movie")
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        internal fun searchQuery(title: String): String = "s=${URLEncoder.encode(title, "UTF-8")}&type=movie"

        /** IMDb id of the search result closest to [year], within [tolerance] years; OMDb order breaks ties. */
        internal fun searchMatch(body: String, year: Int, tolerance: Int = 1): String? = try {
            val results = JSONObject(body).optJSONArray("Search")
            (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                .filter { it.optString("Type") == "movie" && it.optString("imdbID").matches(Regex("tt[0-9]+")) }
                .mapNotNull { r -> r.optString("Year").take(4).toIntOrNull()?.let { r.optString("imdbID") to kotlin.math.abs(it - year) } }
                .filter { it.second <= tolerance }.minByOrNull { it.second }?.first
        } catch (_: Exception) { null }

        internal const val MAX_BODY_BYTES = 64 * 1024

        internal fun readBody(input: InputStream, nanoTime: () -> Long = System::nanoTime): String {
            val start = nanoTime()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                if (nanoTime() - start >= 10_000_000_000L) throw SocketTimeoutException("OMDb response timed out")
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES + 1 - output.size()))
                if (count == -1) return output.toString("UTF-8")
                output.write(buffer, 0, count)
                if (output.size() > MAX_BODY_BYTES) throw IOException("OMDb response too large")
            }
        }

        fun decode(body: String): OmdbResult = decode(body, requireYear = true)

        private fun decode(body: String, requireYear: Boolean): OmdbResult = try {
            val json = JSONObject(body)
            if (json.optString("Response") == "False") {
                val error = json.optString("Error")
                when {
                    error.contains("not found", ignoreCase = true) ||
                        error.contains("too many results", ignoreCase = true) -> OmdbResult.Missing
                    error.contains("limit", ignoreCase = true) -> OmdbResult.Failed("REMOTE_LIMIT")
                    error.contains("api key", ignoreCase = true) -> OmdbResult.Failed("AUTH")
                    else -> OmdbResult.Failed("RESPONSE")
                }
            } else {
                val year = json.optString("Year").toIntOrNull()
                if (year == null || !requireYear) OmdbResult.Missing else OmdbResult.Found(OmdbMovie(
                    json.optString("Type"), year,
                    json.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.0..10.0 },
                    json.optString("imdbVotes").replace(",", "").toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    json.optString("imdbID"),
                ))
            }
        } catch (_: Exception) { OmdbResult.Failed("BAD_RESPONSE") }
    }
}
