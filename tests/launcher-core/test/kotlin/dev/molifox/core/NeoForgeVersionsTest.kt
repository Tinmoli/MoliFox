// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// NeoForge 版本列表改用 Maven 的 maven-metadata.xml；解析时要按游戏版本过滤并保留 beta 标记。
package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class NeoForgeVersionsTest {
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
    fun `neoforge metadata is filtered by the game version prefix`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        <metadata>
                          <versioning>
                            <versions>
                              <version>21.1.209</version>
                              <version>21.1.208</version>
                              <version>21.1.210-beta</version>
                              <version>21.0.167</version>
                              <version>20.6.1</version>
                            </versions>
                          </versioning>
                        </metadata>
                        """.trimIndent(),
                    ),
                )
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertEquals(
                        listOf(
                            LoaderVersion("21.1.210-beta", stable = false),
                            LoaderVersion("21.1.209"),
                            LoaderVersion("21.1.208"),
                        ),
                        catalog.loaderVersions(LoaderType.NeoForge, "1.21.1"),
                    )
                    assertEquals(
                        "/releases/net/neoforged/neoforge/maven-metadata.xml",
                        server.takeRequest().path,
                    )
                }
            }
        }

    @Test
    fun `one twenty one uses the legacy forge project`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse().setBody(
                        """
                        <metadata>
                          <versioning>
                            <versions>
                              <version>1.20.1-47.1.106</version>
                              <version>1.20.1-47.1.107-beta</version>
                              <version>47.1.106</version>
                            </versions>
                          </versioning>
                        </metadata>
                        """.trimIndent(),
                    ),
                )
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertEquals(
                        listOf(
                            LoaderVersion("1.20.1-47.1.107-beta", stable = false),
                            LoaderVersion("1.20.1-47.1.106"),
                        ),
                        catalog.loaderVersions(LoaderType.NeoForge, "1.20.1"),
                    )
                    assertEquals(
                        "/releases/net/neoforged/forge/maven-metadata.xml",
                        server.takeRequest().path,
                    )
                }
            }
        }
}
