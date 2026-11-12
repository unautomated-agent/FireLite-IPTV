package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.R
import dev.firetvlite.iptv.data.film.OmdbHttpClient
import dev.firetvlite.iptv.data.film.OmdbResult
import dev.firetvlite.iptv.data.film.omdbIssueMessage
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OmdbHttpClientTest {
    @Test fun queryParametersEncodePolishAndOriginalTitlesWithoutIncludingAKey() {
        assertEquals("t=By%C5%82+sobie+ch%C5%82opiec&y=2002&type=movie",
            OmdbHttpClient.movieQuery("Był sobie chłopiec", 2002))
        assertEquals("t=Under+Siege+2%3A+Dark+Territory&y=1995&type=movie",
            OmdbHttpClient.movieQuery("Under Siege 2: Dark Territory", 1995))
    }
    @Test fun realOmdbResponseShapesDistinguishUnratedMissingQuotaAndAuthentication() {
        val unrated = OmdbHttpClient.decode("""{"Response":"True","Title":"Film","Type":"movie","Year":"2002","imdbID":"tt123","imdbRating":"N/A","imdbVotes":"N/A"}""") as OmdbResult.Found
        assertNull(unrated.movie.rating)
        assertEquals(0L, unrated.movie.votes)
        assertEquals(OmdbResult.Missing, OmdbHttpClient.decode("""{"Response":"False","Error":"Movie not found!"}"""))
        assertEquals(OmdbResult.Failed("REMOTE_LIMIT"), OmdbHttpClient.decode("""{"Response":"False","Error":"Request limit reached!"}"""))
        assertEquals(OmdbResult.Failed("AUTH"), OmdbHttpClient.decode("""{"Response":"False","Error":"Invalid API key!"}"""))
        assertEquals(OmdbResult.Failed("AUTH"), OmdbHttpClient.decode("""{"Response":"False","Error":"No API key provided."}"""))
        assertEquals(R.string.omdb_issue_auth, omdbIssueMessage("HTTP_401"))
        assertEquals(R.string.omdb_issue_auth, omdbIssueMessage("AUTH"))
        assertEquals(R.string.omdb_issue_limit, omdbIssueMessage("REMOTE_LIMIT"))
        assertEquals(R.string.omdb_issue_limit, omdbIssueMessage("LOCAL_BUDGET"))
        assertEquals(R.string.omdb_issue_error, omdbIssueMessage("NETWORK"))
        assertNull(omdbIssueMessage(null))
    }
    @Test fun boundedBodyAcceptsExactLimitAndRejectsAnExtraByte() {
        val limit = OmdbHttpClient.MAX_BODY_BYTES
        assertEquals(limit, OmdbHttpClient.readBody(ByteArrayInputStream(ByteArray(limit) { 32 })).length)
        assertEquals("", OmdbHttpClient.readBody(ByteArrayInputStream(byteArrayOf())))
        assertThrows(IOException::class.java) {
            OmdbHttpClient.readBody(ByteArrayInputStream(ByteArray(limit + 1)))
        }
    }

    @Test fun tricklingBodyCannotExtendTheReadDeadline() {
        var time = 0L
        val input = object : ByteArrayInputStream(ByteArray(10000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                time += 5_000_000_000L
                return super.read(buffer, offset, minOf(length, 1))
            }
        }
        assertThrows(SocketTimeoutException::class.java) { OmdbHttpClient.readBody(input) { time } }
        assertEquals(2, 10000 - input.available())
    }

    @Test fun httpResponsesAreBoundedAndErrorsRemainNonFatal(): Unit = runBlocking {
        val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val response = AtomicReference(200 to """{"Response":"False","Error":"Movie not found!"}""".toByteArray())
        val serving = FutureTask<Unit> {
            repeat(5) {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val (status, body) = response.get()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status Response\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }
        thread(isDaemon = true) { serving.run() }
        try {
            val client = OmdbHttpClient { "http://127.0.0.1:${server.localPort}/" }
            assertEquals(OmdbResult.Missing, client.lookup("fixture", "Film", 2002))
            response.set(200 to ByteArray(OmdbHttpClient.MAX_BODY_BYTES + 1) { 32 })
            assertEquals(OmdbResult.Failed(), client.lookup("fixture", "Film", 2002))
            response.set(200 to byteArrayOf())
            assertEquals(OmdbResult.Failed("BAD_RESPONSE"), client.lookup("fixture", "Film", 2002))
            response.set(503 to byteArrayOf())
            assertEquals(OmdbResult.Failed("HTTP_503"), client.lookup("fixture", "Film", 2002))
            response.set(401 to """{"Response":"False","Error":"Invalid API key!"}""".toByteArray())
            assertEquals(OmdbResult.Failed("HTTP_401"), client.lookup("fixture", "Film", 2002))
            serving.get(10, TimeUnit.SECONDS)
        } finally { server.close() }
    }
}
