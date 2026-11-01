// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用本地下载服务验证原版与整合包的真实安装落点和提交时目录快照。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SelectedDirectoryInstallTest {
    @TempDir lateinit var directory: Path

    private fun archive(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    @Test
    fun `real downloads and pack overrides use chosen root even after another root is selected`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val jar = archive(mapOf("fixture/Main.class" to byteArrayOf(1, 2, 3)))
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
                val (profile, metadata) = versionPayloads(base, jar)
                server.dispatcher = localDispatcher(metadata, profile, jar)
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                val selected = directory.resolve("chosen-game").apply { createDirectories() }
                val later = directory.resolve("other-game").apply { createDirectories() }
                val directories = GameDirectoryService(store)
                directories.add(selected)
                val selectedId = assertNotNull(store.data.activeGameDirectoryId)
                NetworkClient(source = { "official" }).use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            "$base/manifest.json",
                        )
                    store.updateState {
                        it.copy(jobs = listOf(DownloadJob("install", "Local installation")))
                    }
                    val context = JobContext(store, "install")
                    installVanillaIntoSelected(installer, store, selected, jar, context)
                    directories.add(later)
                    importPackIntoSelected(store, network, installer, context, selectedId)
                    verifyPackLanding(store, selected, later, selectedId)
                }
            }
        }

    /** 本地 mock 的版本清单与版本 JSON，下载地址都指向同一台服务器。 */
    private fun versionPayloads(
        base: String,
        jar: ByteArray,
    ): Pair<String, String> {
        val profile =
            gson.toJson(
                mapOf(
                    "id" to "1.20.1",
                    "type" to "release",
                    "mainClass" to "fixture.Main",
                    "libraries" to emptyList<Any>(),
                    "downloads" to
                        mapOf(
                            "client" to
                                mapOf(
                                    "url" to "$base/client.jar",
                                    "sha1" to sha1(jar),
                                    "size" to jar.size,
                                ),
                        ),
                ),
            )
        val metadata =
            gson.toJson(
                mapOf(
                    "versions" to
                        listOf(
                            mapOf(
                                "id" to "1.20.1",
                                "type" to "release",
                                "url" to "$base/version.json",
                                "sha1" to sha1(profile.toByteArray()),
                            ),
                        ),
                ),
            )
        return profile to metadata
    }

    private fun localDispatcher(
        metadata: String,
        profile: String,
        jar: ByteArray,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(metadata)
                    "/version.json" -> MockResponse().setBody(profile)
                    "/client.jar" -> MockResponse().setBody(Buffer().write(jar))
                    else -> MockResponse().setResponseCode(404)
                }
        }

    /** 原版安装同样只发布一个目录：配置与 JAR 都在实例目录里，基础版本目录被回收。 */
    private suspend fun installVanillaIntoSelected(
        installer: MinecraftInstaller,
        store: LauncherStore,
        selected: Path,
        jar: ByteArray,
        context: JobContext,
    ) {
        val vanilla = installer.createInstance(InstallRequest("Vanilla", "1.20.1"))
        installer.install(vanilla.id, context, freshInstall = true)
        assertTrue(store.instance(vanilla.id).installed)
        assertContentEquals(
            jar,
            selected.resolve("versions/Vanilla/Vanilla.jar").readBytes(),
        )
        assertTrue(selected.resolve("versions/Vanilla/Vanilla.json").isRegularFile())
        assertFalse(selected.resolve("versions/1.20.1").exists())
    }

    /** 只有索引与一层 overrides 的整合包，用来验证落点而不触网。 */
    private fun emptyPackArchive(): Path {
        val index =
            gson.toJson(
                mapOf(
                    "formatVersion" to 1,
                    "game" to "minecraft",
                    "versionId" to "1.0",
                    "name" to "Pack in selected root",
                    "dependencies" to mapOf("minecraft" to "1.20.1"),
                    "files" to emptyList<Any>(),
                ),
            )
        return directory.resolve("fixture.mrpack").apply {
            writeBytes(
                archive(
                    mapOf(
                        "modrinth.index.json" to index.toByteArray(),
                        "overrides/config/marker.txt" to "pack content".toByteArray(),
                    ),
                ),
            )
        }
    }

    /** 导入时显式指定目录，即使之后又选中了另一个目录。 */
    private suspend fun importPackIntoSelected(
        store: LauncherStore,
        network: NetworkClient,
        installer: MinecraftInstaller,
        context: JobContext,
        selectedId: String,
    ) {
        ContentService(store, network, installer)
            .importPack(emptyPackArchive(), context, gameDirectoryId = selectedId)
    }

    /** 整合包内容落进已选目录，之后新选的目录只影响当前设置，不迁移已装文件。 */
    private suspend fun verifyPackLanding(
        store: LauncherStore,
        selected: Path,
        later: Path,
        selectedId: String,
    ) {
        val installedPack = store.data.instances.single { it.name == "Pack in selected root" }
        assertEquals(selectedId, installedPack.gameDirectoryId)
        assertEquals(
            "pack content",
            store
                .instancePath(installedPack.id)
                .resolve("config/marker.txt")
                .readText(),
        )
        assertEquals(selected.toRealPath(), store.minecraftRoot(installedPack))
        assertEquals(
            later.toRealPath(),
            Path.of(
                store.data.gameDirectories
                    .single { it.id == store.data.activeGameDirectoryId }
                    .path,
            ),
        )
        assertFalse(later.resolve("versions").exists())
        assertFalse(store.minecraftRoot.resolve("versions").exists())
    }
}
