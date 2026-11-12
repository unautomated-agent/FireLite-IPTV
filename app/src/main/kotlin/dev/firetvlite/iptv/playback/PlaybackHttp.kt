package dev.firetvlite.iptv.playback

import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal interface ClassifiedHttpFactory : HttpDataSource.Factory {
    fun createDataSource(dataType: Int): HttpDataSource
    override fun createDataSource(): HttpDataSource = createDataSource(C.DATA_TYPE_UNKNOWN)
}

internal data class HttpRequestEvidence(
    val status: Int,
    val contentType: String,
    val classification: String,
    val suffix: String,
    val redirects: Int,
    val hosts: Int,
    val headers: Set<String>,
    val originalId: String,
    val finalId: String,
) {
    fun lines(): List<String> = listOf(
        "HTTP: HTTP=$status; Content-Type=$contentType",
        "Request: $classification; suffix=$suffix",
        "Redirect: ${redirects > 0}; hosts=$hosts; hops=$redirects",
        "Headers: ${headers.sorted().joinToString(",")}",
        "Request ID: $originalId -> $finalId",
    )

    companion object {
        fun classify(dataType: Int, initial: Boolean): String = when (dataType) {
            C.DATA_TYPE_MANIFEST -> if (initial) "master/initial playlist" else "media playlist"
            C.DATA_TYPE_MEDIA, C.DATA_TYPE_MEDIA_INITIALIZATION -> "segment"
            C.DATA_TYPE_DRM -> "key/DRM"
            else -> "media/other"
        }

        fun from(response: Response, root: String, dataType: Int): HttpRequestEvidence {
            val chain = generateSequence(response) { it.priorResponse }.toList().asReversed()
            val original = chain.first().request.url
            val final = response.request.url
            val extension = URI(final.toString()).path.substringAfterLast('.', "").lowercase()
                .takeIf { it in setOf("m3u8", "ts", "m4s", "mp4", "aac", "key") }
            fun id(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
                .take(4).joinToString("") { "%02x".format(it) }
            return HttpRequestEvidence(response.code, response.header("Content-Type").orEmpty().substringBefore(';').take(80),
                classify(dataType, original == root.toHttpUrlOrNull()),
                "…/[redacted]${extension?.let { ".$it" }.orEmpty()}",
                chain.dropLast(1).count { it.code in 300..399 && it.header("Location") != null },
                chain.map { it.request.url.host }.toSet().size,
                chain.flatMap { it.request.headers.names() }.toSet(), id(original.toString()), id(final.toString()))
        }
    }
}

internal object PlaybackHttp {
    private data class Context(val root: String, val type: Int, val report: (HttpRequestEvidence) -> Unit)

    val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            chain.request().tag(Context::class.java)?.let { context ->
                context.report(HttpRequestEvidence.from(response, context.root, context.type))
            }
            response
        }.build()

    fun calls(root: String, dataType: Int, transport: OkHttpClient = client,
        report: (HttpRequestEvidence) -> Unit): Call.Factory = Call.Factory { request ->
        transport.newCall(request.newBuilder().tag(Context::class.java, Context(root, dataType, report)).build())
    }
}
