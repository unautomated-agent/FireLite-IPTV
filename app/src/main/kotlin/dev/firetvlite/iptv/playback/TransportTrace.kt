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
    val status: Int? = null,
    val afterSentMs: Long? = null,
    val hop: Int = 1,
    val hostIndex: Int = 0,
    val redirectHints: List<RedirectHint> = emptyList(),
) {
    val waitingResponse: Boolean get() = phase == TransportPhase.Waiting && sentMs != null &&
        headersMs == null && status == null && protocol != "-"
    fun pending(now: Long) = "pending: ${phase.label} ${(now - phaseSince).coerceIn(0, 999_999)}ms"
    fun line(id: Long): String {
        fun ms(value: Long?) = value?.coerceIn(0, 99_999)?.toString() ?: "-"
        return "Transport: ${id.coerceIn(0, 9999)} $protocol reuse=${reused?.let { if (it) "yes" else "no" } ?: "-"} ua=$uaFields " +
            redirects.takeIf { it.isNotEmpty() }?.joinToString(",", "redirects=", " ").orEmpty() +
            "dns=${ms(dnsMs)} conn=${ms(connectMs)} tls=${ms(tlsMs)} sent=${ms(sentMs)} hdr=${ms(headersMs)}"
    }
    fun headersLine() = "Transport headers: per-channel=$channelHeaders names=${headerNames.joinToString(",").ifEmpty { "-" }}"
    fun responseLine(id: Long) = "Provider response: HTTP=${status ?: "-"} req=${id.coerceIn(0, 9999)} hop=$hop host=$hostIndex sent+=${afterSentMs ?: "-"}ms"
    fun redirectsLine() = "Transport redirects: " + redirectHints.joinToString("; ") {
        "${it.hop}/host${it.hostIndex} query=${if (it.query) "yes" else "no"} token-like=${if (it.tokenLike) "yes" else "no"}"
    }
}

internal data class RedirectHint(val hop: Int, val hostIndex: Int, val query: Boolean, val tokenLike: Boolean)

internal class TransportTrace(channelHeaders: Set<String>, private val report: (TransportSnapshot) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : EventListener() {
    private val started = clock()
    private var dnsStart: Long? = null
    private var connectStart: Long? = null
    private var tlsStart: Long? = null
    private var redirects = 0
    private val hosts = linkedMapOf<String, Int>()
    private fun hostIndex(host: String): Int = hosts[host] ?: if (hosts.size < 21)
        (hosts.size + 1).also { hosts[host] = it } else 0
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
        it.copy(sentMs = elapsed(started), headersMs = null, status = null, afterSentMs = null,
            hop = (redirects + 1).coerceAtMost(21), hostIndex = hostIndex(request.url.host),
            uaFields = request.headers.values("User-Agent").size.coerceAtMost(9))
    }
    override fun responseHeadersStart(call: Call) = update(TransportPhase.Waiting)
    @Synchronized override fun responseHeadersEnd(call: Call, response: Response) =
        update(if (response.code in 300..399 && response.header("Location") != null) TransportPhase.Redirect else TransportPhase.Body) {
            val target = response.header("Location")?.takeIf { response.code in 300..399 }?.let(response.request.url::resolve)
            val hint = target?.let { url ->
                RedirectHint((++redirects + 1).coerceAtMost(21), hostIndex(url.host), url.encodedQuery != null,
                    url.queryParameterNames.any { name -> TOKEN_PARAM.containsMatchIn(name) })
            }
            it.copy(headersMs = elapsed(started), protocol = protocol(response.protocol),
                status = response.code, afterSentMs = it.sentMs?.let { sent -> (clock() - started - sent).coerceIn(0, 999_999) },
                redirectHints = if (hint == null) it.redirectHints else (it.redirectHints + hint).takeLast(4),
                redirects = if (response.code in 300..399 && response.header("Location") != null)
                    (it.redirects + response.code).takeLast(4) else it.redirects)
        }
    override fun callEnd(call: Call) = update(TransportPhase.Complete)
    override fun callFailed(call: Call, ioe: IOException) = update(TransportPhase.Failed)

    private companion object {
        val TOKEN_PARAM = Regex("(?i)token|auth|signature|expires?|expiry|hdnts|hdnea|hmac|jwt|^sig$|^key$|^st$|^exp$")
        val HEADER_NAMES = listOf("User-Agent", "Referer", "Origin", "Cookie", "Authorization", "Accept", "Accept-Language")
        val seen = WeakHashMap<Connection, Boolean>()
        fun protocol(protocol: Protocol) = when (protocol) {
            Protocol.HTTP_2, Protocol.H2_PRIOR_KNOWLEDGE -> "h2"
            Protocol.HTTP_1_1 -> "http/1.1"
            else -> "other"
        }
    }
}
