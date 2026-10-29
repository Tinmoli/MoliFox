// 使用本地下载服务验证原版与整合包的真实安装落点和提交时目录快照。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
            }
            .toByteArray()

    @Test
    fun `real downloads and pack overrides use chosen root even after another root is selected`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val jar = archive(mapOf("fixture/Main.class" to byteArrayOf(1, 2, 3)))
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
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
                                        )
                                ),
                        )
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
                                    )
                                )
                        )
                    )
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest.json" -> MockResponse().setBody(metadata)
                                "/version.json" -> MockResponse().setBody(profile)
                                "/client.jar" -> MockResponse().setBody(Buffer().write(jar))
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
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
                    val vanilla = installer.createInstance(InstallRequest("Vanilla", "1.20.1"))
                    installer.install(vanilla.id, context)
                    assertTrue(store.instance(vanilla.id).installed)
                    assertContentEquals(
                        jar,
                        selected.resolve("versions/1.20.1/1.20.1.jar").readBytes(),
                    )
                    assertTrue(selected.resolve("versions/Vanilla/Vanilla.json").isRegularFile())
                    directories.add(later)
                    val index =
                        gson.toJson(
                            mapOf(
                                "formatVersion" to 1,
                                "game" to "minecraft",
                                "versionId" to "1.0",
                                "name" to "Pack in selected root",
                                "dependencies" to mapOf("minecraft" to "1.20.1"),
                                "files" to emptyList<Any>(),
                            )
                        )
                    val pack =
                        directory.resolve("fixture.mrpack").apply {
                            writeBytes(
                                archive(
                                    mapOf(
                                        "modrinth.index.json" to index.toByteArray(),
                                        "overrides/config/marker.txt" to
                                            "pack content".toByteArray(),
                                    )
                                )
                            )
                        }
                    ContentService(store, network, installer)
                        .importPack(pack, context, gameDirectoryId = selectedId)
                    val installedPack =
                        store.data.instances.single { it.name == "Pack in selected root" }
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
                                .path
                        ),
                    )
                    assertFalse(later.resolve("versions").exists())
                    assertFalse(store.minecraftRoot.resolve("versions").exists())
                }
            }
        }
}
