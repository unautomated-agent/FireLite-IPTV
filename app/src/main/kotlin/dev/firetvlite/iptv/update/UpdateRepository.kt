package dev.firetvlite.iptv.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to GitHub: finds the newest release and downloads its APK.
 * All methods block, so call them off the main thread.
 *
 * @param repository "owner/name" of the GitHub repository that publishes the releases.
 */
class UpdateRepository(
    private val repository: String,
    private val versionCodeOffset: Int,
) {

    @Throws(IOException::class, org.json.JSONException::class)
    fun fetchLatest(): ReleaseParser.Result {
        val connection = open("https://api.github.com/repos/$repository/releases/latest")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered HTTP $code")
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return ReleaseParser.parse(body, versionCodeOffset)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the APK of [release] to [target]. The file is written to a temporary name first, so a
     * broken download can never be mistaken for a complete one.
     *
     * @param onProgress receives 0..1, or null while the total size is unknown.
     */
    @Throws(IOException::class)
    fun download(release: ReleaseInfo, target: File, onProgress: (Float?) -> Unit) {
        if (!release.apkUrl.startsWith("https://")) throw IOException("Refusing a non-HTTPS download")
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")

        val connection = open(release.apkUrl)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Download answered HTTP $code")
            val total = connection.contentLengthLong.takeIf { it > 0 }
                ?: release.apkSizeBytes.takeIf { it > 0 }

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total == null) {
                            onProgress(null)
                        } else {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(done.toFloat() / total)
                            }
                        }
                    }
                }
            }
            if (total != null && partial.length() != total) {
                throw IOException("Incomplete download: ${partial.length()} of $total bytes")
            }
        } catch (e: IOException) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        if (target.exists() && !target.delete()) throw IOException("Could not replace the old download")
        if (!partial.renameTo(target)) throw IOException("Could not save the download")
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "FireLite")
        return connection
    }
}
