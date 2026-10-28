// 验证更新版本排序、发布来源约束与下载包的实际哈希校验。

package dev.molifox.core

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitHubUpdaterTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `numeric versions and prerelease versions sort correctly`() {
        fun version(text: String) = assertNotNull(VersionNumber.parse(text))
        assertTrue(version("v0.10.0") > version("0.9.9"))
        assertTrue(version("0.3.0") > version("0.3.0-rc.9"))
        assertTrue(version("0.3.0-rc.10") > version("0.3.0-rc.2"))
        assertEquals(version("0.3.0"), version("v0.3.0+build.1"))
        assertNull(VersionNumber.parse("../../bad"))
    }

    private fun release(
        tag: String,
        asset: Map<String, Any> = emptyMap(),
        prerelease: Boolean = false,
    ) =
        mapOf(
            "tag_name" to tag,
            "draft" to false,
            "prerelease" to prerelease,
            "name" to "MoliFox $tag",
            "html_url" to "${MoliFoxIdentity.releases}/tag/$tag",
            "body" to "Release notes",
            "published_at" to "2026-10-28T10:00:00Z",
            "assets" to listOf(asset).filter { it.isNotEmpty() },
        )

    @Test
    fun `check excludes previews and verifies downloaded bytes before reporting success`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val payload = "fixture-update".toByteArray()
                val sha =
                    MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                        "%02x".format(it)
                    }
                val store = LauncherStore(DesktopPlatform(), directory)
                val filename =
                    if (store.platform.osName == "windows") "MoliFox.exe"
                    else "MoliFox-linux-x64.run"
                val asset =
                    mapOf(
                        "name" to filename,
                        "size" to payload.size,
                        "digest" to "sha256:$sha",
                        "browser_download_url" to server.loopbackUrl("/update").toString(),
                    )
                server.enqueue(
                    MockResponse()
                        .setBody(
                            gson.toJson(
                                listOf(
                                    release("v0.10.0", asset),
                                    release("v9.0.0", prerelease = true),
                                    release("v0.9.0"),
                                )
                            )
                        )
                )
                NetworkClient().use { network ->
                    val updater =
                        GitHubUpdater(
                            store,
                            network,
                            server.loopbackUrl("/repos/Tinmoli/MoliFox").toString().trimEnd('/'),
                        )
                    updater.check()
                    assertTrue(store.state.value.update.available)
                    val release = assertNotNull(store.state.value.update.release)
                    assertEquals("v0.10.0", release.version)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    try {
                        server.enqueue(MockResponse().setBody(String(payload)))
                        val jobs = JobQueue(store, scope)
                        jobs.enqueue("Update") { updater.download(release, this) }
                        jobs.awaitIdle()
                        assertEquals(JobStatus.Completed, store.state.value.jobs.single().status)
                        assertContentEquals(
                            payload,
                            Path.of(assertNotNull(store.state.value.update.downloadedFile))
                                .readBytes(),
                        )
                        assertEquals(1, store.state.value.jobs.single().completedFiles)
                        assertEquals(
                            payload.size.toLong(),
                            store.state.value.jobs.single().downloadedBytes,
                        )
                        assertEquals(
                            "/repos/Tinmoli/MoliFox/releases?per_page=100",
                            server.takeRequest().path,
                        )
                    } finally {
                        scope.cancel()
                    }
                }
            }
        }

    @Test
    fun `failed check is visible and untrusted release origins are rejected`(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.start()
            val store = LauncherStore(DesktopPlatform(), directory)
            NetworkClient().use { network ->
                val updater =
                    GitHubUpdater(store, network, server.loopbackUrl("/repos/test").toString())
                updater.check()
                assertTrue(assertNotNull(store.state.value.update.error).contains("受限"))
                assertFalse(store.state.value.update.checking)
                val wrong = gson.toJsonTree(release("v0.4.0")).asJsonObject
                wrong.addProperty(
                    "html_url",
                    "https://github.com/other/project/releases/tag/v0.4.0",
                )
                assertFailsWith<IllegalArgumentException> { updater.parseRelease(wrong) }
            }
        }
    }
}
