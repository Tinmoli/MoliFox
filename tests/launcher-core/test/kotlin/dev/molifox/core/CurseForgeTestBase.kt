// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供 CurseForge 测试共用的临时目录、夹具搭建与整合包构造。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories

/**
 * CurseForge 测试的共享基类：负责搭建一次性的 Store、假服务和 MockWebServer。
 *
 * 拆出来是为了让各测试类只保留用例本身；夹具行为与原 `CurseForgeTest.fixture` 完全一致。
 */
abstract class CurseForgeTestBase {
    @TempDir lateinit var temporary: Path

    /** 起一个 MockWebServer 与独立的 Store，跑完 [action] 后全部关闭。 */
    internal fun fixture(action: suspend CurseForgeFixture.() -> Unit) =
        runBlocking {
            MockWebServer().use { server ->
                val store = newStore()
                val instance = newInstance()
                store.mutate {
                    it.copy(
                        settings =
                            it.settings.copy(
                                contentDownloadSource = "curseforge",
                                curseForgeBaseUrl =
                                    server.loopbackUrl("").toString().trimEnd('/'),
                            ),
                        instances = listOf(instance),
                    )
                }
                NetworkClient().use { network ->
                    val fixture = newFixture(store, server, network, instance)
                    val client = clientJar()
                    val profile = profileJson(server, client)
                    server.dispatcher =
                        CurseForgeMockDispatcher(fixture, server, profile, client)
                    fixture.action()
                }
            }
        }

    private fun newStore(): LauncherStore =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            },
            temporary.resolve("profile"),
        )

    private fun newInstance(): GameInstance =
        GameInstance(
            name = "CurseForge fixture",
            gameVersion = "1.20.1",
            loader = LoaderType.Fabric,
            versionId = "fixture",
            installed = true,
        )

    private fun newFixture(
        store: LauncherStore,
        server: MockWebServer,
        network: NetworkClient,
        instance: GameInstance,
    ): CurseForgeFixture {
        val service =
            ContentService(
                store,
                network,
                MinecraftInstaller(
                    store,
                    network,
                    JavaService(store, network),
                    server.loopbackUrl("/minecraft/manifest").toString(),
                ),
                server.loopbackUrl("/v2").toString(),
            )
        return CurseForgeFixture(
            store,
            server,
            service,
            CurseForgeBackend(store, network),
            instance,
            network,
        )
    }

    private fun clientJar(): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("fixture.txt"))
                zip.write("fixture".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }

    private fun profileJson(
        server: MockWebServer,
        client: ByteArray,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "1.20.1",
                "mainClass" to "fixture.Main",
                "type" to "release",
                "downloads" to
                    mapOf(
                        "client" to
                            mapOf(
                                "url" to
                                    server
                                        .loopbackUrl("/minecraft/client.jar")
                                        .toString(),
                                "size" to client.size,
                                "sha1" to CurseForgeFixture.hash(client),
                            ),
                    ),
                "assetIndex" to
                    mapOf(
                        "id" to "fixture",
                        "url" to server.loopbackUrl("/minecraft/assets").toString(),
                    ),
                "libraries" to emptyList<String>(),
            ),
        )

    internal fun hit() = ContentHit("curseforge:1", "project-1", "Project 1", "", "", 0, "mod", null)

    internal fun manifest(): JsonObject =
        gson
            .toJsonTree(
                mapOf(
                    "manifestType" to "minecraftModpack",
                    "manifestVersion" to 1,
                    "name" to "CF Pack",
                    "version" to "1.0",
                    "author" to "Author",
                    "overrides" to "custom-overrides",
                    "minecraft" to
                        mapOf(
                            "version" to "1.20.1",
                            "modLoaders" to
                                listOf(mapOf("id" to "fabric-0.16.14", "primary" to true)),
                        ),
                    "files" to
                        listOf(
                            mapOf("projectID" to 1, "fileID" to 11, "required" to true),
                            mapOf("projectID" to 2, "fileID" to 21, "required" to false),
                        ),
                ),
            ).asJsonObject

    internal fun pack(
        info: JsonObject,
        entries: Map<String, String> = emptyMap(),
    ): Path {
        val archive = temporary.resolve("${UUID.randomUUID()}.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, content) in mapOf("manifest.json" to gson.toJson(info)) + entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return archive
    }
}
