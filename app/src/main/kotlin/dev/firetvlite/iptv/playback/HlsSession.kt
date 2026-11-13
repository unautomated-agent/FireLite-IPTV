package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UriUtil
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import java.net.URI

internal interface HlsSessionFactory : ClassifiedHttpFactory {
    val hls: HlsSession
}

internal class HlsSession(val archive: Boolean = false, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Request(val id: Long, val uri: String, val kind: String, val extension: String, val started: Long,
        var status: Int? = null, var duration: Long? = null, var transport: TransportSnapshot? = null)
    private val started = clock()
    private val resources = linkedMapOf<String, String>()
    private val requests = ArrayDeque<Request>()
    private val failed = linkedSetOf<String>()
    private val advanced = linkedSetOf<String>()
    private val skipped = linkedSetOf<String>()
    private var sequence = 0L
    private var consecutiveSkips = 0
    private var master: HlsMultivariantPlaylist? = null
    private var media = "HLS media: pending"
    private var selected = -1
    private var failover = false
    private var progressMs = 0L
    private var poolEvicted = false
    private var providerExtended = false
    private var providerOutcome: TransportSnapshot? = null
    @get:Synchronized var hasManifestRequest = false
        private set
    @get:Synchronized val hasMuxedAudio: Boolean get() = master?.muxedAudioFormat != null
    @get:Synchronized val hasPlaylist: Boolean get() = master != null || media != "HLS media: pending"

    @Synchronized fun owns(uri: String): Boolean = uri in resources || requests.any { it.uri == uri }

    @Synchronized fun kind(uri: String, dataType: Int = C.DATA_TYPE_MANIFEST): String = resources[uri] ?: when (dataType) {
        C.DATA_TYPE_MANIFEST -> if (master == null) "master" else "variant"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
        C.DATA_TYPE_DRM -> "key"
        else -> "segment"
    }

    @Synchronized fun begin(uri: String, dataType: Int): Long {
        if (dataType == C.DATA_TYPE_MANIFEST) hasManifestRequest = true
        val request = Request(++sequence, uri, kind(uri, dataType), extension(uri), clock())
        requests.addLast(request)
        if (requests.size > 12) requests.removeFirst()
        return request.id
    }

    @Synchronized fun response(id: Long, status: Int) {
        requests.firstOrNull { it.id == id }?.status = status
    }

    @Synchronized fun transport(id: Long, snapshot: TransportSnapshot): Boolean {
        requests.firstOrNull { it.id == id }?.transport = snapshot
        if (id == 1L && providerExtended && providerOutcome == null &&
            snapshot.phase in setOf(TransportPhase.Body, TransportPhase.Failed)) {
            providerOutcome = if (snapshot.phase == TransportPhase.Failed)
                snapshot.copy(status = null, headersMs = null, afterSentMs = null) else snapshot
            return true
        }
        return false
    }

    @Synchronized fun evicted() { poolEvicted = true }

    @Synchronized fun end(id: Long) {
        requests.firstOrNull { it.id == id }?.let { it.duration = clock() - it.started }
    }

    @Synchronized fun failedRequest(uri: String, status: Int) {
        requests.lastOrNull { it.uri == uri }?.let { it.status = status; it.duration = clock() - it.started }
    }

    @Synchronized fun loaded(uri: String) {
        if (failover && advanced.add(uri)) {
            if (advanced.size > 128) advanced.remove(advanced.first())
            progressMs = clock() - started
        }
        if (kind(uri, C.DATA_TYPE_MEDIA) == "segment") consecutiveSkips = 0
    }

    @Synchronized fun fallback(uri: String): Boolean {
        if (!failed.add(uri)) return false
        if (failed.size > 128) failed.remove(failed.first())
        failover = true
        progressMs = clock() - started
        return true
    }

    @Synchronized fun skip(uri: String): Boolean {
        if (archive || kind(uri, C.DATA_TYPE_MEDIA) != "segment" || consecutiveSkips >= 3 || !skipped.add(uri)) return false
        if (skipped.size > 32) skipped.remove(skipped.first())
        consecutiveSkips++
        fallback(uri)
        return true
    }

    private fun providerRequest(): Request? = requests.firstOrNull {
        !archive && it.id == 1L && it.kind == "master" && it.status == null && it.duration == null &&
            it.transport?.waitingResponse == true
    }

    @Synchronized fun waitingForProvider(): Boolean = providerRequest()?.transport?.let {
        clock() - it.phaseSince >= 5_000
    } == true

    @Synchronized fun timeoutAt(baseMs: Long): Long {
        if (providerRequest() != null) {
            providerExtended = true
            return PROVIDER_WAIT_MS
        }
        return HlsStartupBudget.timeoutAt(baseMs, progressMs.takeIf { failover })
    }

    @Synchronized fun providerTimeout(): String? = providerRequest()?.let {
        "Provider wait: provider no response after ${((clock() - started) / 1000).coerceIn(0, 999)}s"
    }

    @Synchronized fun parsed(playlist: HlsMultivariantPlaylist): HlsMultivariantPlaylist {
        master = playlist
        register(playlist.baseUri, "master")
        playlist.variants.forEach { register(it.url.toString(), "variant") }
        playlist.audios.forEach { it.url?.let { uri -> register(uri.toString(), "audio") } }
        playlist.subtitles.forEach { it.url?.let { uri -> register(uri.toString(), "subs") } }
        return HlsMultivariantPlaylist(playlist.baseUri, playlist.tags, playlist.variants, playlist.videos,
            playlist.audios.filterNot { hasMuxedAudio && it.url?.toString() in failed },
            playlist.subtitles.filterNot { it.url?.toString() in failed }, playlist.closedCaptions,
            playlist.muxedAudioFormat, playlist.muxedCaptionFormats, playlist.hasIndependentSegments,
            playlist.variableDefinitions, playlist.sessionKeyDrmInitData)
    }

    @Synchronized fun parsed(playlist: HlsMediaPlaylist) {
        if (playlist.baseUri !in resources) register(playlist.baseUri, "variant")
        playlist.segments.forEach { segment ->
            register(UriUtil.resolve(playlist.baseUri, segment.url), "segment")
            segment.initializationSegment?.let { register(UriUtil.resolve(playlist.baseUri, it.url), "init") }
            segment.fullSegmentEncryptionKeyUri?.let { register(UriUtil.resolve(playlist.baseUri, it), "key") }
        }
        val declared = playlist.tags.lastOrNull { it.startsWith("#EXT-X-KEY:") }
            ?.substringAfter("METHOD=", "")?.substringBefore(',')
        val encryption = declared?.takeIf { it in setOf("NONE", "AES-128", "SAMPLE-AES", "SAMPLE-AES-CENC", "SAMPLE-AES-CTR") } ?: when {
            playlist.segments.any { it.fullSegmentEncryptionKeyUri != null } -> "AES-128"
            playlist.protectionSchemes != null -> "DRM"
            else -> "NONE"
        }
        media = "HLS media: target=${playlist.targetDurationUs / 1000}ms; segments=${playlist.segments.size}; " +
            "live=${!playlist.hasEndTag}; endlist=${playlist.hasEndTag}; encryption=$encryption; " +
            "map=${playlist.segments.any { it.initializationSegment != null }}; ext=${playlist.segments.firstOrNull()?.url?.let(::extension) ?: "-"}"
    }

    @Synchronized fun selected(format: Format) {
        val index = master?.variants?.indexOfFirst { it.format.id == format.id } ?: -1
        if (index >= 0) selected = index
    }

    @Synchronized fun lines(): List<String> = buildList {
        val variants = master?.variants.orEmpty()
        add("HLS master: variants=${variants.size}; selected=${selected.takeIf { it >= 0 } ?: "pending"}")
        variants.take(8).forEachIndexed { index, variant ->
            val f = variant.format
            add("HLS variant: $index ${(f.bitrate / 1000).coerceIn(0, 999_999)}k ${f.width.coerceIn(0, 9999)}x${f.height.coerceIn(0, 9999)} ${codecs(f.codecs)}")
        }
        val renditions = master?.let { p ->
            p.audios.map { "audio/${language(it.format.language)}/uri=${it.url != null}/default=${it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0}" } +
                p.subtitles.map { "subs/${language(it.format.language)}/uri=${it.url != null}/default=${it.format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0}" } +
                listOfNotNull(p.muxedAudioFormat?.let { "audio/${language(it.language)}/uri=false/default=${it.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0}" })
        }.orEmpty()
        add("HLS renditions: count=${renditions.size}")
        renditions.take(8).forEach { add("HLS rendition: $it") }
        addAll(media.split("; encryption=").let { parts ->
            listOf(parts.first()) + parts.getOrNull(1)?.let { listOf("HLS media detail: encryption=$it") }.orEmpty()
        })
        add("HLS failover: active=$failover; failures=${failed.size}; skipped=${skipped.size}")
        if (providerExtended) {
            val outcome = providerOutcome
            add("Provider wait: extended=45s outcome=" + when {
                outcome?.status != null -> "HTTP${outcome.status} headers=${outcome.headersMs}ms"
                outcome?.phase == TransportPhase.Failed -> "IO failure"
                else -> "pending"
            })
            outcome?.let {
                add(it.responseLine(1))
                if (it.redirectHints.isNotEmpty()) add(it.redirectsLine())
            }
        }
        if (poolEvicted) add("Transport pool: idle evicted before close grace")
        requests.firstNotNullOfOrNull { it.transport }?.let { add(it.headersLine()) }
        requests.lastOrNull { it.status == null && it.duration == null && it.transport != null }?.transport?.let {
            add("Transport ${it.pending(clock())}")
        }
        requests.forEach {
            val state = if (it.status == null && it.duration == null && it.transport != null)
                it.transport!!.pending(clock()) else
                "${it.status ?: if (it.duration == null) "pending" else "IO"} ${(it.duration ?: (clock() - it.started)).coerceIn(0, 999_999)}ms"
            add("HLS request: ${it.kind} $state ${it.extension}")
            it.transport?.let { snapshot -> add(snapshot.line(it.id)) }
            if ((it.id == 1L && providerOutcome == null) || (it.id != 1L && (it.transport?.status ?: 0) >= 400)) {
                it.transport?.takeIf { snapshot -> snapshot.status != null && snapshot.phase != TransportPhase.Redirect }
                    ?.let { snapshot -> add(snapshot.responseLine(it.id)) }
                it.transport?.takeIf { snapshot -> snapshot.redirectHints.isNotEmpty() }?.let { snapshot -> add(snapshot.redirectsLine()) }
            }
        }
    }

    private fun register(uri: String, kind: String) {
        resources[uri] = kind
        if (resources.size > 256) resources.remove(resources.keys.first())
    }

    companion object {
        const val PROVIDER_WAIT_MS = 45_000L
        fun extension(uri: String): String = runCatching { URI(uri).path.substringAfterLast('.', "").lowercase() }
            .getOrNull()?.takeIf { it in setOf("m3u8", "ts", "m4s", "mp4", "aac", "vtt", "webvtt", "key") } ?: "-"
        private fun language(value: String?): String = value?.substringBefore('-')?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "und"
        private fun codecs(value: String?): String = value.orEmpty().split(',').map { it.trim().substringBefore('.') }
            .filter { it in setOf("avc1", "avc3", "hvc1", "hev1", "av01", "vp09", "mp4a", "ac-3", "ec-3", "opus") }
            .distinct().joinToString("+").ifEmpty { "unknown" }
    }
}

internal object HlsStartupBudget {
    fun timeoutAt(baseMs: Long, progressMs: Long?): Long {
        val base = baseMs.coerceAtLeast(1)
        return if (progressMs == null) base else maxOf(base, minOf(base * 3, progressMs.coerceAtLeast(0) + base))
    }
}
