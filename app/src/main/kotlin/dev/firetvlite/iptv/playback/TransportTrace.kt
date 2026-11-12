package dev.firetvlite.iptv.playback

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.WeakHashMap

internal enum class TransportPhase(val label: String) {
    Queued("queued"), Dns("DNS"), Connecting("connecting"), Tls("TLS"), Connected("connected"),
    Sending("sending request"), Waiting("waiting response"), Redirect("redirecting"),
    Body("reading body"), Complete("complete"), Failed("failed"),
}

internal data class TransportSnapshot(
    val phase: TransportPhase,
    val phaseSince: Long,
    val dnsMs: Long? = null,
    val connectMs: Long? = null,
    val tlsMs: Long? = null,
    val sentMs: Long? = null,
    val headersMs: Long? = null,
    val protocol: String = "-",
    val reused: Boolean? = null,
    val uaFields: Int = 0,
    val redirects: List<Int> = emptyList(),
    val channelHeaders: Boolean = false,
    val headerNames: List<String> = emptyList(),
) {
    fun pending(now: Long) = "pending: ${phase.label} ${(now - phaseSince).coerceIn(0, 999_999)}ms"
    fun line(id: Long): String {
        fun ms(value: Long?) = value?.coerceIn(0, 99_999)?.toString() ?: "-"
        return "Transport: ${id.coerceIn(0, 9999)} $protocol reuse=${reused?.let { if (it) "yes" else "no" } ?: "-"} ua=$uaFields " +
            redirects.takeIf { it.isNotEmpty() }?.joinToString(",", "redirects=", " ").orEmpty() +
            "dns=${ms(dnsMs)} conn=${ms(connectMs)} tls=${ms(tlsMs)} sent=${ms(sentMs)} hdr=${ms(headersMs)}"
    }
    fun headersLine() = "Transport headers: per-channel=$channelHeaders names=${headerNames.joinToString(",").ifEmpty { "-" }}"
}

internal class TransportTrace(channelHeaders: Set<String>, private val report: (TransportSnapshot) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : EventListener() {
    private val started = clock()
    private var dnsStart: Long? = null
    private var connectStart: Long? = null
    private var tlsStart: Long? = null
    private var snapshot = TransportSnapshot(TransportPhase.Queued, started, channelHeaders = channelHeaders.isNotEmpty(),
        headerNames = HEADER_NAMES.filter { allowed -> channelHeaders.any { it.equals(allowed, true) } })

    @Synchronized private fun update(phase: TransportPhase = snapshot.phase,
        change: (TransportSnapshot) -> TransportSnapshot = { it }) {
        val since = if (phase == snapshot.phase) snapshot.phaseSince else clock()
        snapshot = change(snapshot).copy(phase = phase, phaseSince = since)
        report(snapshot)
    }
    private fun elapsed(start: Long?) = start?.let { (clock() - it).coerceIn(0, 99_999) }

    override fun callStart(call: Call) = update()
    @Synchronized override fun dnsStart(call: Call, domainName: String) {
        dnsStart = clock()
        update(TransportPhase.Dns)
    }
    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) =
        update(TransportPhase.Connecting) { it.copy(dnsMs = elapsed(dnsStart)) }
    @Synchronized override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectStart = clock()
        update(TransportPhase.Connecting)
    }
    @Synchronized override fun secureConnectStart(call: Call) {
        tlsStart = clock()
        update(TransportPhase.Tls)
    }
    override fun secureConnectEnd(call: Call, handshake: Handshake?) =
        update(TransportPhase.Connecting) { it.copy(tlsMs = elapsed(tlsStart)) }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
        update(TransportPhase.Connected) { it.copy(connectMs = elapsed(connectStart)) }
    override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy,
        protocol: Protocol?, ioe: IOException) = update(TransportPhase.Connecting) { it.copy(connectMs = elapsed(connectStart)) }
    override fun connectionAcquired(call: Call, connection: Connection) {
        val reused = synchronized(seen) { seen.put(connection, true) == true }
        update(TransportPhase.Connected) { it.copy(protocol = protocol(connection.protocol()), reused = reused) }
    }
    override fun requestHeadersStart(call: Call) = update(TransportPhase.Sending)
    override fun requestHeadersEnd(call: Call, request: Request) = update(TransportPhase.Waiting) {
        it.copy(sentMs = elapsed(started), uaFields = request.headers.values("User-Agent").size.coerceAtMost(9))
    }
    override fun responseHeadersStart(call: Call) = update(TransportPhase.Waiting)
    override fun responseHeadersEnd(call: Call, response: Response) =
        update(if (response.code in 300..399 && response.header("Location") != null) TransportPhase.Redirect else TransportPhase.Body) {
            it.copy(headersMs = elapsed(started), protocol = protocol(response.protocol),
                redirects = if (response.code in 300..399 && response.header("Location") != null)
                    (it.redirects + response.code).takeLast(4) else it.redirects)
        }
    override fun callEnd(call: Call) = update(TransportPhase.Complete)
    override fun callFailed(call: Call, ioe: IOException) = update(TransportPhase.Failed)

    private companion object {
        val HEADER_NAMES = listOf("User-Agent", "Referer", "Origin", "Cookie", "Authorization", "Accept", "Accept-Language")
        val seen = WeakHashMap<Connection, Boolean>()
        fun protocol(protocol: Protocol) = when (protocol) {
            Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE -> "h2"
            Protocol.HTTP_1_1 -> "http/1.1"
            else -> "other"
        }
    }
}
