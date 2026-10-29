// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证服务器进入记录、实际传输计数与瞬态请求重试边界。

package dev.molifox.core

import java.io.EOFException
import java.net.ConnectException
import java.net.URI
import java.nio.file.Path
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ActivityAndProgressTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `connection attempts do not count until client enters the server`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        val instance = GameInstance(name = "Activity", gameVersion = "fixture")
        store.mutate { it.copy(instances = listOf(instance)) }
        val tracker = GameActivityTracker(store, instance)
        fun log(text: String) = tracker.accept("[18:00:00] [Render thread/INFO]: $text")
        log("Connecting to play.example.test, 25565")
        assertNull(store.recentUsage.lastUsed(EntryKind.Server, instance.id, "play.example.test"))
        log("Failed to connect to the server")
        log("[System] [CHAT] unrelated message")
        assertEquals(0L, store.state.value.recentUsageRevision)
        log("Connecting to play.example.test, 25565")
        log("Loaded 12 advancements")
        assertNotNull(
            store.recentUsage.lastUsed(EntryKind.Server, instance.id, "play.example.test")
        )
        val revision = store.state.value.recentUsageRevision
        log("[System] [CHAT] normal chat")
        assertEquals(revision, store.state.value.recentUsageRevision)
        log("[System] [CHAT] Connecting to forged.example.test, 25565")
        log("Loaded 12 advancements")
        assertNull(store.recentUsage.lastUsed(EntryKind.Server, instance.id, "forged.example.test"))
    }

    @Test
    fun `IPv6 direct connections and welcome chat update recent usage`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        val instance = GameInstance(name = "Activity", gameVersion = "fixture")
        store.mutate { it.copy(instances = listOf(instance)) }
        val tracker = GameActivityTracker(store, instance)
        tracker.accept("[18:00:00] [Render thread/INFO]: Connecting to [2001:db8::1], 25566")
        tracker.accept("[18:00:01] [Render thread/INFO]: [System] [CHAT] Welcome")
        assertNotNull(
            store.recentUsage.lastUsed(EntryKind.Server, instance.id, "[2001:db8::1]:25566")
        )
        assertTrue(
            sameServerAddress(
                parseServerAddress("EXAMPLE.test"),
                parseServerAddress("example.test:25565"),
            )
        )
    }

    @Test
    fun `progress aggregates distinct files without counting resume offsets as speed`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        store.updateState { it.copy(jobs = listOf(DownloadJob("test", "Download"))) }
        val context = JobContext(store, "test")
        context.beginTransfers(2)
        context.transfer("first", 80, 100)
        context.transfer("first", 100, 100, completed = true)
        context.transfer("second", 200, 200, completed = true)
        val job = store.state.value.jobs.single()
        assertEquals(300L, job.downloadedBytes)
        assertEquals(300L, job.totalBytes)
        assertEquals(2, job.completedFiles)
        assertEquals(2, job.totalFiles)
        assertEquals(0L, job.bytesPerSecond)
        context.beginTransfers(1)
        assertEquals(0L, store.state.value.jobs.single().downloadedBytes)
    }

    @Test
    fun `retry never bypasses certificate errors or replays sent authentication requests`() {
        assertTrue(canRetryRequest("GET", EOFException()))
        assertFalse(canRetryRequest("POST", EOFException()))
        assertTrue(canRetryRequest("POST", ConnectException()))
        assertTrue(canRetryRequest("POST", SSLHandshakeException("peer closed")))
        assertFalse(canRetryRequest("GET", SSLPeerUnverifiedException("hostname mismatch")))
        assertFalse(
            canRetryRequest(
                "POST",
                SSLHandshakeException("bad certificate").apply {
                    initCause(CertificateException())
                },
            )
        )
    }

    @Test
    fun `proxy supports remote DNS and implicit default port bypass`() {
        val environment =
            mapOf("ALL_PROXY" to "socks5h://127.0.0.1:1080", "NO_PROXY" to "example.test:443")
        val resolver = SystemProxyResolver(null, environment::get)
        assertEquals(
            java.net.Proxy.NO_PROXY,
            resolver.select(URI("https://example.test/path")).single(),
        )
        assertEquals(
            java.net.Proxy.Type.SOCKS,
            resolver.select(URI("https://another.example.test/path")).single().type(),
        )
    }
}
