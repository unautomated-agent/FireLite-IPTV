package dev.firetvlite.iptv.playback

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal class HealthConnectionFixture : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val workers = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    val paths = CopyOnWriteArrayList<String>()
    val base = "http://localhost:${server.localPort}"

    init {
        workers.submit {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets += socket
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                workers.submit {
                    try {
                        socket.use {
                            val input = socket.getInputStream().bufferedReader()
                            val path = input.readLine().split(' ')[1]
                            while (!input.readLine().isNullOrEmpty()) Unit
                            paths += path
                            if (path != "/slow") {
                                val status = if (path == "/bad") "404 Not Found" else "200 OK"
                                val body = "x".repeat(if (path == "/hold") 5000 else 4096)
                                val length = if (path == "/hold") 100_000 else body.length
                                socket.getOutputStream().apply {
                                    write(("HTTP/1.1 $status\r\nContent-Type: video/mp2t\r\n" +
                                        "Content-Length: $length\r\nConnection: close\r\n\r\n$body").toByteArray())
                                    flush()
                                }
                            }
                            if (path == "/slow" || path == "/hold") while (input.read() >= 0) Unit
                        }
                    } catch (_: Exception) {
                        socket.close()
                    } finally {
                        sockets -= socket
                        active.decrementAndGet()
                    }
                }
            }
        }
    }

    override fun close() {
        server.close()
        sockets.forEach { it.close() }
        workers.shutdownNow()
    }
}
