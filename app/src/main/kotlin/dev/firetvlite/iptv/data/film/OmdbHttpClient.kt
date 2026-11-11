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
    override suspend fun lookup(key: String, title: String, year: Int): OmdbResult = withContext(Dispatchers.IO) {
        try {
            val query = listOf("apikey" to key, "t" to title, "y" to year.toString(), "type" to "movie")
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
            val connection = URL(baseUrl().trimEnd('/') + "/?" + query).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            try {
                if (connection.responseCode != 200) OmdbResult.Failed else {
                    val body = connection.inputStream.use { readBody(it) }
                    decode(body)
                }
            } finally { connection.disconnect() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            OmdbResult.Failed
        }
    }

    companion object {
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

        fun decode(body: String): OmdbResult = try {
            val json = JSONObject(body)
            if (json.optString("Response") == "False") {
                if (json.optString("Error").contains("not found", ignoreCase = true)) OmdbResult.Missing else OmdbResult.Failed
            } else {
                val year = json.optString("Year").toIntOrNull()
                if (year == null) OmdbResult.Missing else OmdbResult.Found(OmdbMovie(
                    json.optString("Type"), year,
                    json.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.0..10.0 },
                    json.optString("imdbVotes").replace(",", "").toLongOrNull()?.coerceAtLeast(0) ?: 0,
                    json.optString("imdbID"),
                ))
            }
        } catch (_: Exception) { OmdbResult.Failed }
    }
}
