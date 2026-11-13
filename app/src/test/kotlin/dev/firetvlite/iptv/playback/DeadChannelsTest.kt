package dev.firetvlite.iptv.playback

import dev.firetvlite.iptv.data.library.DiagnosticsReport
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeadChannelsTest {
    @get:Rule val folder = TemporaryFolder()
    private val request = StreamRequest("https://provider.test/live/123456789/password/1.m3u8", "TV", channelName = "Republika HD")

    @Test fun marksCountsPersistsAndClearsLiveIdentityFromArchive() = runBlocking {
        val file = File(folder.root, "channels.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 100L
        val registry = DeadChannels(file, scope) { now }
        try {
            registry.flush()
            assertTrue(registry.state.value.entries.isEmpty())
            registry.result(request, "HTTP 404")
            now = 200
            registry.result(request, "timeout", "health check")
            registry.checkSummary("11-13 12:00 · Filmy · 1/2 · martwe 1 · running")
            registry.flush()
            val entry = registry.state.value.entries.single()
            assertEquals(setOf(request.channelKey), registry.state.value.keys)
            assertEquals(100L, entry.first)
            assertEquals(200L, entry.last)
            assertEquals(2, entry.count)
            assertEquals("health check", entry.source)
            assertEquals("timeout", entry.reason)
            assertEquals("Republika HD", entry.name)
            val restored = DeadChannels(file, scope)
            restored.flush()
            assertEquals(listOf(entry), restored.state.value.entries)
            assertTrue(restored.state.value.check.endsWith("stopped"))
            restored.result(request.copy(url = "https://provider.test/archive/other", archive = true), null)
            restored.flush()
            assertTrue(restored.state.value.entries.isEmpty())
            assertTrue(restored.state.value.keys.isEmpty())
            val cleared = DeadChannels(file, scope)
            cleared.flush()
            assertTrue(cleared.state.value.entries.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun evictsOldestAtOneHundredAndDoesNotPersistSecrets() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(folder.root, "channels.json")
        var now = 1L
        val registry = DeadChannels(file, scope) { now++ }
        try {
            repeat(105) { index ->
                registry.result(StreamRequest("https://provider.test/$index", "TV", channelName = "Kanał $index"), "HTTP 404")
            }
            registry.flush()
            assertEquals(100, registry.state.value.entries.size)
            assertEquals("Kanał 104", registry.state.value.entries.first().name)
            assertFalse(registry.state.value.entries.any { it.name == "Kanał 0" })
            registry.result(request.copy(channelName = "TV 123456789 password ${request.url}\nnext"), "HTTP 404 ${request.url}")
            registry.flush()
            val saved = file.readText()
            for (secret in listOf("123456789", "password", "provider.test", request.url)) assertFalse(saved, saved.contains(secret))
            assertEquals(100, registry.state.value.entries.size)
        } finally { scope.cancel() }
    }

    @Test fun corruptPersistenceStartsEmptyAndCanBeReplaced() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = folder.newFile("channels.json").apply { writeText("invalid json") }
        val registry = DeadChannels(file, scope)
        try {
            registry.flush()
            assertEquals(ChannelHealthState(), registry.state.value)
            registry.result(request, "HTTP 404")
            registry.flush()
            assertEquals(1, registry.state.value.entries.size)
            assertTrue(file.readText().contains("Republika HD"))
        } finally { scope.cancel() }
    }

    @Test fun completedHealthCheckPersistsResultsSourceAndScopeAndRemovesHealthyEntry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val registry = DeadChannels(File(folder.root, "channels.json"), scope)
        val bad = StreamRequest("https://example.test/bad", "TV", channelName = "Bad")
        val healthy = StreamRequest("https://example.test/good", "TV", channelName = "Healthy")
        val controller = PlaybackController(this, { error("No player") }, {},
            evictConnections = {}, healthProbe = { channel, _, _ -> if (channel == bad) "HTTP 404" else null },
            healthResult = { channel, reason -> registry.result(channel, reason, "health check") }, healthSummary = registry::checkSummary)
        try {
            registry.result(healthy, "timeout")
            controller.startHealthCheck("Filmy", 2) { if (it == 0) bad else healthy }
            withTimeout(5000) { while (controller.healthCheck.value.running) delay(10) }
            registry.flush()
            val entry = registry.state.value.entries.single()
            assertEquals(bad.channelKey, entry.key)
            assertEquals("HTTP 404", entry.reason)
            assertEquals("health check", entry.source)
            assertTrue(controller.healthCheck.value.complete)
            assertEquals(2, controller.healthCheck.value.checked)
            assertTrue(registry.state.value.check.contains("Filmy · 2/2 · martwe 1 · complete"))
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun qrCompactionRetainsAllTenChannelsAndScopedCheckOutcome() {
        val entries = (0..9).map { DeadChannel("key", "Kanał Żółć $it", 1, 1, 3, "HTTP 404", "health check") }
        val summary = ChannelHealthState(entries, "11-13 12:00 · Filmy · 8/10 · martwe 3 · stopped").qrSummary()
        val noise = "Transport pending: waiting response 9000ms\nProvider response: HTTP=404 hop=3 host#2\n" +
            "HLS request: media pending 9000ms\n".repeat(12) + "Channel: test\n" + "frame Żółć\n".repeat(2000)
        val payload = DiagnosticsReport.payload(noise + summary)
        assertTrue(payload.toByteArray().size <= 1800)
        for (index in 0..9) assertTrue(payload, payload.contains("Kanał Żółć $index 404"))
        for (text in listOf("Martwe (10)", "Zdrowie kanałów:", "Filmy", "8/10", "martwe 3", "stopped"))
            assertTrue(payload, payload.contains(text))
        assertFalse(payload.contains("więcej"))
        assertFalse(payload.contains("last 01-01"))
        assertFalse(payload.contains("×3"))
        assertEquals("Martwe kanały: 0", ChannelHealthState().summary())
    }

    @Test fun compactQrListsEveryNameForThirtyAndOneHundredShortEntriesBeforeOtherDiagnostics() {
        val noise = "Transport pending: waiting response 9000ms\nProvider response: HTTP=404 hop=3 host#2\n" +
            "Transport headers: headers=User-Agent,Referer\nTransport: http/1.1 reuse=no\n" +
            "HLS request: media pending 9000ms\n".repeat(12) + "Channel: test\n" + "frame Żółć\n".repeat(2000)
        for (count in listOf(30, 100)) {
            val entries = (1..count).map { DeadChannel("key$it", "TV${it.toString().padStart(3, '0')}", 1, 1, 50, "HTTP 404", "playback") }
            val payload = DiagnosticsReport.payload(noise + ChannelHealthState(entries).qrSummary())
            assertTrue(payload.toByteArray().size <= 1800)
            for (entry in entries) assertTrue(payload, payload.contains("${entry.name} 404"))
            assertFalse(payload.contains("więcej"))
        }
    }

    @Test fun longUnicodeNamesShrinkBeforeAnyEntryIsOmittedAndFallbackIsExplicit() {
        val entries = (1..100).map { DeadChannel("key$it", "TV${it.toString().padStart(3, '0')} Żółć🎬".repeat(5),
            1, 1, 50, "network error", "playback") }
        val summary = ChannelHealthState(entries).qrSummary()
        val payload = DiagnosticsReport.payload(summary + "\n" + "HLS request: " + "x".repeat(10_000))
        assertTrue(payload.toByteArray().size <= 1800)
        for (index in 1..100) assertTrue(payload, payload.contains("TV${index.toString().padStart(3, '0')}"))
        assertFalse(payload.contains("więcej"))
        assertFalse(payload.contains('\uFFFD'))
        val fallback = ChannelHealthState(entries).qrSummary(100)
        assertTrue(fallback.toByteArray().size <= 100)
        assertTrue(fallback.contains("więcej"))
    }

    @Test fun deviceDetailsIncludeEveryEntryWithReasonCountTimeAndSourceButQrOmitsPrivateData() {
        val entries = (1..30).map { DeadChannel("key$it", "Kanał $it", 1, 1, it, "HTTP 404", "health check") }
        val details = ChannelHealthState(entries).summary()
        for (entry in entries) assertTrue(details.contains("${entry.name} · HTTP 404 · ×${entry.count} · last 01-01 01:00 · health check"))
        assertFalse(details.contains("więcej"))
        val unsafe = DeadChannel("key", "TV 123456789 password ${request.url}", 1, 1, 1, "network error", "playback")
        val qr = ChannelHealthState(listOf(unsafe)).qrSummary()
        assertFalse(qr.contains("https://"))
        assertFalse(qr.contains("123456789"))
        assertFalse(qr.contains("provider.test"))
        assertTrue(qr.contains("sieć"))
        assertEquals("Martwe (0): ", ChannelHealthState().qrSummary())
    }
}
