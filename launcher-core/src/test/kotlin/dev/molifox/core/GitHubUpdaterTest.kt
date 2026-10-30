// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证更新版本排序、发布来源约束与下载包的实际哈希校验。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    ) = mapOf(
        "tag_name" to tag,
        "draft" to false,
        "prerelease" to prerelease,
        "name" to "MoliFox $tag",
        "html_url" to "${MoliFoxIdentity.RELEASES}/tag/$tag",
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
                val store = LauncherStore(DesktopPlatform(), directory)
                val filename =
                    if (store.platform.osName == "windows") {
                        "MoliFox.exe"
                    } else {
                        "MoliFox-linux-x64.run"
                    }
                val asset = updateAsset(server, payload, filename)
                server.enqueue(
                    MockResponse()
                        .setBody(
                            gson.toJson(
                                listOf(
                                    release("v0.9.0"),
                                    release("v9.0.0", prerelease = true),
                                    release("v0.10.0", asset),
                                ),
                            ),
                        ),
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
                    assertEquals("Release notes", release.notes)
                    downloadAndVerify(store, updater, release, server, payload)
                }
            }
        }

    /** 正式版安装包的资产描述：大小与 digest 由实际字节算出。 */
    private fun updateAsset(
        server: MockWebServer,
        payload: ByteArray,
        filename: String,
    ): Map<String, Any> {
        val sha =
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                "%02x".format(it)
            }
        return mapOf(
            "name" to filename,
            "size" to payload.size,
            "digest" to "sha256:$sha",
            "browser_download_url" to server.loopbackUrl("/update").toString(),
        )
    }

    /** 在任务队列里下载更新包，并核对任务状态、落盘字节与请求地址。 */
    private suspend fun downloadAndVerify(
        store: LauncherStore,
        updater: GitHubUpdater,
        release: LauncherRelease,
        server: MockWebServer,
        payload: ByteArray,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            server.enqueue(MockResponse().setBody(String(payload)))
            val jobs = JobQueue(store, scope)
            jobs.enqueue("Update") { updater.download(release, this) }
            jobs.awaitIdle()
            assertEquals(
                JobStatus.Completed,
                store.state.value.jobs
                    .single()
                    .status,
            )
            assertContentEquals(
                payload,
                Path.of(assertNotNull(store.state.value.update.downloadedFile)).readBytes(),
            )
            assertEquals(
                1,
                store.state.value.jobs
                    .single()
                    .completedFiles,
            )
            assertEquals(
                payload.size.toLong(),
                store.state.value.jobs
                    .single()
                    .downloadedBytes,
            )
            assertEquals(
                "/repos/Tinmoli/MoliFox/releases?per_page=100",
                server.takeRequest().path,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed check is visible and untrusted release origins are rejected`(): Unit =
        runBlocking {
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
