// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证缺少下载地址的版本 JSON 仍按 Maven/版本清单补齐，且可选文件失败不中止安装。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * 回归：piston-data 只服务版本 JSON 明确列出的文件；依赖库必须按 Maven 坐标解析，
 * 客户端 JAR 必须来自 `downloads.client.url`，日志配置这类可选文件的 404 不能中止安装。
 */
class MissingDownloadResolutionTest {
    @TempDir lateinit var temporary: Path

    private fun library(name: String, extra: Map<String, Any> = emptyMap()): Map<String, Any> =
        mapOf("name" to name) + extra

    @Test
    fun `version json without downloads resolves libraries through maven and never piston-data`() {
        val root = temporary.resolve(".minecraft")
        writeJson(
            root.resolve("versions/26.3/26.3.json"),
            mapOf(
                "id" to "26.3",
                "mainClass" to "net.minecraft.client.main.Main",
                "libraries" to
                    listOf(
                        // 完全没有 downloads 段：按坐标取 Maven。
                        library("commons-io:commons-io:2.20.0"),
                        // 只有 path、没有 url：仍然按 Maven 仓库拼地址。
                        library(
                            "org.jspecify:jspecify:1.0.0",
                            mapOf(
                                "downloads" to
                                    mapOf(
                                        "artifact" to
                                            mapOf(
                                                "path" to
                                                    "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"
                                            )
                                    )
                            ),
                        ),
                        // 错误地指向对象存储的库地址：改回该库声明的 Maven 仓库，不能请求 piston-data。
                        library(
                            "com.example:mapped:1.0",
                            mapOf(
                                "downloads" to
                                    mapOf(
                                        "artifact" to
                                            mapOf(
                                                "url" to
                                                    "https://piston-data.mojang.com/v1/objects/deadbeef/mapped-1.0.jar",
                                                "path" to "com/example/mapped/1.0/mapped-1.0.jar",
                                            )
                                    )
                            ),
                        ),
                        // 加载器库只声明了自己的 Maven 仓库。
                        library(
                            "net.fabricmc:fabric-loader:0.20.1",
                            mapOf("url" to "https://maven.fabricmc.net/"),
                        ),
                    ),
            ),
        )
        val resolver = VersionResolver(root, DesktopPlatform())
        val profile = resolver.resolve("26.3")
        val artifacts = resolver.libraries(profile).flatMap { resolver.libraryArtifacts(it) }
        assertEquals(
            listOf(
                "https://libraries.minecraft.net/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar",
                "https://libraries.minecraft.net/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
                "https://libraries.minecraft.net/com/example/mapped/1.0/mapped-1.0.jar",
                "https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar",
            ),
            artifacts.map { it.first.url },
        )
        assertTrue(
            artifacts.none { "piston-data" in it.first.url },
            "依赖库不能从对象存储按路径猜地址",
        )
        assertEquals(
            listOf(
                "commons-io-2.20.0.jar",
                "jspecify-1.0.0.jar",
                "mapped-1.0.jar",
                "fabric-loader-0.20.1.jar",
            ),
            artifacts.map { it.first.target.fileName.toString() },
        )
        artifacts.forEach { assertTrue(it.first.target.startsWith(root.resolve("libraries"))) }
    }

    @Test
    fun `missing downloads client is resolved from the real version json not a guessed path`() =
        runBlocking {
            val root = temporary.resolve(".minecraft")
            // 基础版本 JSON 没有 downloads.client；只有 id 和主类。
            writeJson(
                root.resolve("versions/26.3/26.3.json"),
                mapOf(
                    "id" to "26.3",
                    "mainClass" to "net.minecraft.client.main.Main",
                    "libraries" to emptyList<String>(),
                ),
            )
            writeJson(
                root.resolve("versions/Broken/Broken.json"),
                mapOf(
                    "id" to "Broken",
                    "inheritsFrom" to "26.3",
                    "libraries" to emptyList<String>(),
                ),
            )
            MockWebServer().use { server ->
                val versionUrl = server.loopbackUrl("/v1/packages/26.3.json")
                server.enqueue(
                    MockResponse()
                        .setBody(
                            gson.toJson(
                                mapOf(
                                    "latest" to
                                        mapOf("release" to "26.3", "snapshot" to "26.3"),
                                    "versions" to
                                        listOf(
                                            mapOf(
                                                "id" to "26.3",
                                                "type" to "release",
                                                "url" to versionUrl.toString(),
                                                "releaseTime" to "2026-09-15T11:23:02+00:00",
                                            )
                                        ),
                                )
                            )
                        )
                )
                server.enqueue(
                    MockResponse()
                        .setBody(
                            gson.toJson(
                                mapOf(
                                    "id" to "26.3",
                                    "downloads" to
                                        mapOf(
                                            "client" to
                                                mapOf(
                                                    "url" to
                                                        "https://cdn.example.invalid/real-26.3-client.jar",
                                                    "sha1" to "a".repeat(40),
                                                    "size" to 123L,
                                                )
                                        ),
                                )
                            )
                        )
                )
                NetworkClient(source = { "official" }).use { network ->
                    val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            manifestUrl = server.loopbackUrl("/manifest.json").toString(),
                        )
                    val profile = VersionResolver(root, DesktopPlatform()).resolve("Broken")
                    val url = installer.clientJarUrl(profile, root)
                    assertEquals(
                        "https://cdn.example.invalid/real-26.3-client.jar",
                        url,
                        "必须使用版本 JSON 的 downloads.client.url，不能按版本名猜对象存储路径",
                    )
                    assertFalse("piston-data" in url)
                    server.takeRequest()
                    assertEquals("/v1/packages/26.3.json", server.takeRequest().path)
                }
            }
        }

    @Test
    fun `optional log config 404 is logged and does not abort the required installation`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
            val context = JobContext(store, "test")
            val required = temporary.resolve("libraries/kept.txt")
            required.parent.createDirectories()
            required.writeText("kept")
            val optional = temporary.resolve("assets/log_configs/client-1.21.2.xml")
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(404))
                server.enqueue(MockResponse().setResponseCode(404))
                NetworkClient(source = { "official" }).use { network ->
                    val verifier = GameFileVerifier(network, 2)
                    val warnings = mutableListOf<String>()
                    verifier.ensure(
                        listOf(DownloadArtifact("https://cdn.example.invalid/kept.jar", required)),
                        context,
                        InstallPhase.Libraries,
                        optional =
                            listOf(
                                DownloadArtifact(server.loopbackUrl("/client-1.21.2.xml").toString(), optional)
                            ),
                        warn = { warnings += it },
                    )
                    assertTrue(optional.notExists(), "可选文件失败后保持缺失")
                    assertEquals(1, warnings.size)
                    assertTrue("可选文件未补全" in warnings.single())
                    assertTrue(optional.toString() in warnings.single())

                    // 同一套逻辑下，必需文件 404 仍然要明确失败。
                    val failure =
                        assertFailsWith<IOException> {
                            verifier.ensure(
                                listOf(
                                    DownloadArtifact(
                                        server.loopbackUrl("/missing-required.jar").toString(),
                                        temporary.resolve("libraries/missing.jar"),
                                    )
                                ),
                                context,
                                InstallPhase.Libraries,
                            )
                        }
                    assertTrue("游戏文件自动补全失败" in (failure.message ?: ""))
                    assertTrue(
                        "missing.jar" in (failure.message ?: ""),
                        "失败信息必须点名缺失的必需文件",
                    )
                }
            }
        }
}