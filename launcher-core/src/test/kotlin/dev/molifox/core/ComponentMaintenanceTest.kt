// 负责验证模组加载器更换与回退的行为与边界。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ComponentMaintenanceTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `instances without a game version cannot be relabelled as a different loader`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            NetworkClient().use { network ->
                val installer = MinecraftInstaller(store, network, JavaService(store, network))
                for (instance in
                    listOf(
                        GameInstance(name = "External", installed = true, externalProfile = true),
                        GameInstance(name = "Pack", installed = true, packFile = "linked.mrpack"),
                    )) {
                    store.mutate { it.copy(instances = listOf(instance)) }
                    val before = store.data
                    assertFails {
                        installer.changeLoader(
                            instance.id,
                            LoaderType.Fabric,
                            "0.16.14",
                            JobContext(store, "test"),
                        )
                    }
                    assertEquals(before, store.data)
                }
            }
        }

    @Test
    fun `cancelled component download preserves installed selection and previous backup`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "Owned",
                    installed = true,
                    gameVersion = "fixture",
                    versionId = "old",
                    loader = LoaderType.Fabric,
                    loaderVersion = "old",
                    previousComponents = ComponentSelection(LoaderType.Vanilla, "", "fixture"),
                )
            store.mutate { it.copy(instances = listOf(instance)) }
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                NetworkClient().use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            server.loopbackUrl("/manifest").toString(),
                        )
                    val changing =
                        launch(Dispatchers.IO) {
                            installer.changeLoader(
                                instance.id,
                                LoaderType.Vanilla,
                                "",
                                JobContext(store, "test"),
                            )
                        }
                    assertNotNull(
                        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
                    )
                    changing.cancelAndJoin()
                    assertEquals(instance, store.instance(instance.id))
                }
            }
        }

    @Test
    fun `successful switch records a reversible selection and validates dependencies before restoring`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
            val instance =
                GameInstance(
                    name = "Owned",
                    gameVersion = "fixture",
                    versionId = "old-profile",
                    loader = LoaderType.Fabric,
                    loaderVersion = "old",
                    installed = true,
                    managedGameDirectory = true,
                    externalProfile = true,
                    packFile = "linked.mrpack",
                )
            store.mutate { it.copy(instances = listOf(instance)) }
            val game = store.instancePath(instance.id).apply { createDirectories() }
            game
                .resolve("mods")
                .createDirectories()
                .resolve("keep.jar")
                .writeText("user mod remains")
            val client =
                ByteArrayOutputStream().use { output ->
                    JarOutputStream(output).use { jar ->
                        jar.putNextEntry(JarEntry("fixture.txt"))
                        jar.write("fixture".toByteArray())
                        jar.closeEntry()
                    }
                    output.toByteArray()
                }
            MockWebServer().use { server ->
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
                val profile =
                    gson.toJson(
                        mapOf(
                            "id" to "fixture",
                            "mainClass" to "FixtureBase",
                            "libraries" to emptyList<Any>(),
                            "downloads" to
                                mapOf(
                                    "client" to
                                        mapOf("url" to "$base/client.jar", "size" to client.size)
                                ),
                        )
                    )
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest.json" ->
                                    MockResponse()
                                        .setBody(
                                            gson.toJson(
                                                mapOf(
                                                    "versions" to
                                                        listOf(
                                                            mapOf(
                                                                "id" to "fixture",
                                                                "type" to "release",
                                                                "url" to "$base/profile.json",
                                                            )
                                                        )
                                                )
                                            )
                                        )
                                "/profile.json" -> MockResponse().setBody(profile)
                                "/client.jar" -> MockResponse().setBody(Buffer().write(client))
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                writeJson(
                    store.minecraftRoot.resolve("versions/old-profile/old-profile.json"),
                    mapOf(
                        "id" to "old-profile",
                        "inheritsFrom" to "fixture",
                        "mainClass" to "FixtureOld",
                    ),
                )
                NetworkClient().use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            "$base/manifest.json",
                        )
                    installer.changeLoader(
                        instance.id,
                        LoaderType.Vanilla,
                        "",
                        JobContext(store, "test"),
                    )
                    val changed = store.instance(instance.id)
                    assertEquals(LoaderType.Vanilla, changed.loader)
                    assertEquals(instance.componentSelection(), changed.previousComponents)
                    installer.changeLoader(
                        instance.id,
                        LoaderType.Vanilla,
                        "",
                        JobContext(store, "test"),
                    )
                    assertEquals(
                        changed.previousComponents,
                        store.instance(instance.id).previousComponents,
                    )
                    installer.restoreComponents(instance.id, JobContext(store, "test"))
                    val restored = store.instance(instance.id)
                    assertEquals(instance.componentSelection(), restored.componentSelection())
                    assertEquals(changed.componentSelection(), restored.previousComponents)
                    assertEquals(
                        "FixtureOld",
                        VersionResolver(store.minecraftRoot, store.platform)
                            .resolve(restored.versionId)
                            .mainClass,
                    )
                    assertEquals("user mod remains", game.resolve("mods/keep.jar").readText())
                    installer.restoreComponents(instance.id, JobContext(store, "test"))
                    val current = store.instance(instance.id)
                    store.minecraftRoot
                        .resolve("versions/old-profile/old-profile.json")
                        .deleteExisting()
                    store.directories.instanceBackups
                        .resolve("components/${current.componentBackupId}/0.json")
                        .writeText("corrupt backup")
                    assertFails {
                        installer.restoreComponents(instance.id, JobContext(store, "test"))
                    }
                    assertEquals(current, store.instance(instance.id))
                }
            }
        }
}
