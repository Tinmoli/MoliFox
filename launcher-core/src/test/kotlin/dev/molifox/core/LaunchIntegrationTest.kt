// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实际 Java 子进程与启动配置的集成的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeBytes
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchIntegrationTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `install repair and launch a complete fixture through a real Java process`(): Unit =
        runBlocking {
            val directory =
                temporary.resolve("中文 launcher with spaces").apply { createDirectories() }
            val fixture = LaunchFixtureServer(temporary)
            MockWebServer().use { server ->
                fixture.serve(server)
                LaunchFixtureScenario(directory, fixture, server).run()
            }
        }

    @Test
    fun `pack traversal is rejected before files can escape instance storage`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("data"))
            val network = NetworkClient()
            try {
                val installer = MinecraftInstaller(store, network, JavaService(store, network))
                val pack = temporary.resolve("bad.mrpack")
                val json =
                    mapOf(
                        "formatVersion" to 1,
                        "game" to "minecraft",
                        "name" to "Bad",
                        "dependencies" to mapOf("minecraft" to "1.20.1"),
                        "files" to
                            listOf(
                                mapOf(
                                    "path" to "../../escape.jar",
                                    "hashes" to mapOf("sha1" to "a".repeat(40)),
                                    "downloads" to listOf("https://cdn.modrinth.com/test.jar"),
                                ),
                            ),
                    )
                pack.writeBytes(jar("modrinth.index.json", gson.toJson(json).toByteArray()))
                assertFails {
                    ContentService(store, network, installer)
                        .importPack(pack, JobContext(store, "test"))
                }
                assertTrue(store.data.instances.isEmpty())
                assertFalse(temporary.resolve("escape.jar").exists())
            } finally {
                network.close()
            }
        }
}
