// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证下载源与版本清单源的选择规则的行为与边界。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownloadSourcePolicyTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `fresh settings choose automatic sources and manual sources remain distinct`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        assertEquals("auto", store.data.settings.downloadSource)
        assertEquals("auto", store.data.settings.versionListSource)
        NetworkClient().use { network ->
            val url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            assertEquals(
                listOf(url, "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"),
                network.downloadCandidates(url, "auto"),
            )
            assertEquals(listOf(url), network.downloadCandidates(url, "official"))
            assertEquals(
                listOf("https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"),
                network.downloadCandidates(url, "bmclapi"),
            )
            assertEquals(
                listOf("https://api.modrinth.com/v2/project/test"),
                network.downloadCandidates("https://api.modrinth.com/v2/project/test", "auto"),
            )
        }
    }

    @Test
    fun `version-list override never reads the unrelated game download source`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"versions\":[]}"))
            NetworkClient(source = { error("file source must not control the version list") })
                .use { network ->
                    val result =
                        network.json(
                            server.loopbackUrl("/manifest").toString(),
                            sourceOverride = "official",
                        )
                    assertTrue(result.asJsonObject.array("versions").isEmpty)
                    assertEquals("/manifest", server.takeRequest().path)
                }
        }
    }

    @Test
    fun `explicit root override installs there without changing the user's current directory`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        val external = temporary.resolve("external").createDirectory()
        external.resolve("keep.txt").writeText("keep")
        val directory = GameDirectory(path = external.toString())
        store.mutate {
            it.copy(gameDirectories = listOf(directory), activeGameDirectoryId = directory.id)
        }
        NetworkClient().use { network ->
            val installer = MinecraftInstaller(store, network, JavaService(store, network))
            val instance =
                installer.createInstance(InstallRequest("New game", "26.3"), gameDirectoryId = null)
            assertNull(instance.gameDirectoryId)
            assertTrue(store.instancePath(instance.id).startsWith(temporary.resolve(".minecraft")))
            assertEquals(directory.id, store.data.activeGameDirectoryId)
        }
        assertEquals(listOf("keep.txt"), external.listDirectoryEntries().map { it.name })
        assertEquals("keep", external.resolve("keep.txt").readText())
    }
}
