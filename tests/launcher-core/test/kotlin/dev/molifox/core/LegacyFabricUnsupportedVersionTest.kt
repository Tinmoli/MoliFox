// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// LegacyFabric 元数据对不支持的正式版返回 400：版本列表应为空，而不是把 400 当成网络故障。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyFabricUnsupportedVersionTest {
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
    fun `legacy fabric unsupported game returns an empty loader list`() =
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400))
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertEquals(
                        emptyList(),
                        catalog.loaderVersions(LoaderType.LegacyFabric, "1.21.1"),
                    )
                }
            }
        }

    @Test
    fun `a 400 for fabric still surfaces as an error`() {
        runBlocking {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400))
                NetworkClient().use { network ->
                    val catalog = catalog("http://127.0.0.1:${server.port}", network)
                    assertFailsWith<java.io.IOException> {
                        catalog.loaderVersions(LoaderType.Fabric, "1.21.1")
                    }
                }
            }
        }
    }
}
