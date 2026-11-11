package dev.firetvlite.iptv.data

import com.sun.net.httpserver.HttpServer
import dev.firetvlite.iptv.data.film.OmdbHttpClient
import dev.firetvlite.iptv.data.film.OmdbResult
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OmdbHttpClientTest {
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

    @Test fun httpResponsesAreBoundedAndErrorsRemainNonFatal() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var body = """{"Response":"False","Error":"Movie not found!"}""".toByteArray()
        var status = 200
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(status, 0)
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = OmdbHttpClient { "http://127.0.0.1:${server.address.port}/" }
            assertEquals(OmdbResult.Missing, client.lookup("fixture", "Film", 2002))
            body = ByteArray(OmdbHttpClient.MAX_BODY_BYTES + 1) { 32 }
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
            body = byteArrayOf()
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
            status = 503
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
        } finally { server.stop(0) }
    }
}
