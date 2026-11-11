package dev.firetvlite.iptv.data

import dev.firetvlite.iptv.data.film.OmdbHttpClient
import dev.firetvlite.iptv.data.film.OmdbResult
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
            repeat(4) {
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
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
            response.set(200 to byteArrayOf())
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
            response.set(503 to byteArrayOf())
            assertEquals(OmdbResult.Failed, client.lookup("fixture", "Film", 2002))
            serving.get(10, TimeUnit.SECONDS)
        } finally { server.close() }
    }
}
