// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 受限加载器只展示其支持的游戏版本；不限制的加载器返回 null。
package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SupportedGameVersionsTest {
    @TempDir lateinit var temporary: Path

    private fun catalog(
        base: String,
        network: NetworkClient,
    ) = MinecraftVersionCatalog(
        store = LauncherStore(DesktopPlatform(), temporary.resolve("profile")),
        network = network,
        manifestUrl = "$base/mc/game/version_manifest_v2.json",
        fabricMetaBase = base,
        quiltMetaBase = base,
        legacyFabricMetaBase = base,
        forgeMavenBase = base,
        neoforgeMavenBase = base,
    )

    @Test
    fun `legacy fabric returns the game versions from its metadata`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        [
                          {"version":"1.13.2","stable":true},
                          {"version":"1.12.2","stable":true},
                          {"version":"","stable":false}
                        ]
                        """.trimIndent(),
                    ),
                )
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertEquals(
                        setOf("1.13.2", "1.12.2"),
                        catalog.supportedGameVersions(LoaderType.LegacyFabric),
                    )
                }
            }
        }

    @Test
    fun `cleanroom is limited to 1_12_2`() =
        runBlocking {
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertEquals(
                        setOf("1.12.2"),
                        catalog.supportedGameVersions(LoaderType.Cleanroom),
                    )
                }
            }
        }

    @Test
    fun `vanilla and unrestricted loaders return null`() =
        runBlocking {
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertNull(catalog.supportedGameVersions(LoaderType.Vanilla))
                    assertNull(catalog.supportedGameVersions(LoaderType.Fabric))
                }
            }
        }
}
