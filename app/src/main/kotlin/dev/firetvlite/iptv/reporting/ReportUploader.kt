package dev.firetvlite.iptv.reporting

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.GsonBuilder
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal interface ReportPreferences {
    val install: String
    var enabled: Boolean
    var lastSent: Long
    var lastError: String
}

internal data class ReportingStatus(val enabled: Boolean = true, val pending: Int = 0, val lastSent: Long = 0,
    val lastError: String = "", val blocked: Boolean = false)

internal data class UploadOutcome(val retryIn: Long = 0, val blocked: Boolean = false)

internal object ReportBatch {
    const val MAX_BYTES = 60_000
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun item(report: QueuedReport) = JsonObject().apply {
        addProperty("id", report.id); addProperty("kind", report.kind); addProperty("occurredAt", report.occurredAt)
        addProperty("channel", report.channel); addProperty("summary", report.summary); addProperty("detail", report.detail)
    }

    fun json(install: String, reports: List<QueuedReport>): String {
        val build = reports.first().build
        require(reports.all { it.build == build })
        val items = JsonArray()
        reports.forEach { report ->
            items.add(item(report))
        }
        return gson.toJson(JsonObject().apply {
            addProperty("install", install); addProperty("build", build); add("reports", items)
        })
    }

    fun next(install: String, pending: List<QueuedReport>): List<QueuedReport> {
        val batch = mutableListOf<QueuedReport>()
        val build = pending.firstOrNull()?.build ?: return batch
        for (report in pending.filter { it.build == build }.take(50)) {
            if (json(install, batch + report).toByteArray(Charsets.UTF_8).size >= MAX_BYTES) break
            batch.add(report)
        }
        return batch
    }
}

internal class ReportUploader(
    private val queue: ReportQueue,
    private val preferences: ReportPreferences,
    private val allowed: () -> Boolean,
    private val publish: (ReportingStatus) -> Unit = {},
    private val endpoint: String = ENDPOINT,
    suppliedClient: OkHttpClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val http by lazy { suppliedClient ?: client() }
    private var retryAt = 0L
    private var backoff = 60_000L

    fun status(blocked: Boolean = false) = ReportingStatus(preferences.enabled, queue.count(),
        preferences.lastSent, preferences.lastError, blocked)

    suspend fun upload(manual: Boolean = false): UploadOutcome = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                if (!manual && !preferences.enabled) { publish(status()); return@withLock UploadOutcome() }
                if (queue.count() == 0) { publish(status()); return@withLock UploadOutcome() }
                if (!allowed()) { publish(status(true)); return@withLock UploadOutcome(2_000, true) }
                val remaining = retryAt - clock()
                if (remaining > 0) { publish(status()); return@withLock UploadOutcome(remaining) }
                val batch = ReportBatch.next(preferences.install, queue.pending(50))
                if (batch.isEmpty()) return@withLock UploadOutcome()
                val outcome = send(batch, true, manual)
                if (outcome.blocked) {
                    publish(status(true))
                    return@withLock outcome
                }
                if (outcome.retryIn > 0) {
                    retryAt = clock() + backoff
                    backoff = (backoff * 2).coerceAtMost(3_600_000)
                    publish(status())
                    return@withLock UploadOutcome(retryAt - clock())
                }
                retryAt = 0
                backoff = 60_000
                publish(status())
                UploadOutcome(if (queue.count() > 0) 2_000 else 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                runCatching { preferences.lastError = "Błąd lokalnej kolejki"; publish(status()) }
                UploadOutcome(60_000)
            }
        }
    }

    private suspend fun send(batch: List<QueuedReport>, split: Boolean, manual: Boolean): UploadOutcome {
        if (!manual && !preferences.enabled) return UploadOutcome()
        if (!allowed()) return UploadOutcome(2_000, true)
        val interrupted = AtomicBoolean(false)
        val call = http.newCall(Request.Builder().url(endpoint).header("x-firelite-app", "firelite-iptv")
            .post(ReportBatch.json(preferences.install, batch).toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        val code = try {
            coroutineScope {
                val guard = launch {
                    while (isActive) {
                        if (!allowed() || (!manual && !preferences.enabled)) {
                            interrupted.set(true); call.cancel(); break
                        }
                        delay(100)
                    }
                }
                try {
                    if (!allowed()) { interrupted.set(true); call.cancel() }
                    execute(call)
                } finally { guard.cancel() }
            }
        } catch (_: IOException) {
            if (!manual && !preferences.enabled) return UploadOutcome()
            if (interrupted.get() || !allowed()) return UploadOutcome(2_000, true)
            preferences.lastError = "Błąd sieci"
            return UploadOutcome(1)
        }
        return when (code) {
            200 -> {
                queue.acknowledge(batch)
                preferences.lastSent = clock()
                preferences.lastError = ""
                UploadOutcome()
            }
            400, 413 -> {
                if (split && batch.size > 1) {
                    val half = batch.size / 2
                    val first = send(batch.take(half), false, manual)
                    if (first.retryIn > 0) first else send(batch.drop(half), false, manual)
                } else {
                    queue.remove(batch.map { it.id })
                    preferences.lastError = "HTTP $code · pominięto ${batch.size}"
                    UploadOutcome()
                }
            }
            else -> {
                preferences.lastError = "HTTP $code"
                UploadOutcome(1)
            }
        }
    }

    private suspend fun execute(call: Call): Int = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) { continuation.resumeWith(Result.failure(error)) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resumeWith(runCatching { response.use { it.code } })
            }
        })
    }

    companion object {
        const val ENDPOINT = "https://firelite-reports.eventcam.workers.dev/v1/reports"
        fun client(): OkHttpClient = OkHttpClient.Builder().connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    }
}
