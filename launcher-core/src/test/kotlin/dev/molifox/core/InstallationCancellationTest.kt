// 使用实际 HTTP 文件和任务队列验证取消后的版本、下载和共享文件清理。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstallationCancellationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `queued cancellation removes the reserved instance even before installation begins`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
            store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            NetworkClient().use { network ->
                val installer = MinecraftInstaller(store, network, JavaService(store, network))
                val instance = installer.createInstance(InstallRequest("Queued", "1.20.1"))
                val folder = store.versionGamePath(instance)
                assertTrue(folder.isDirectory())
                val queue = JobQueue(store, scope)
                val ready = CompletableDeferred<Unit>()
                val resources = taskResourcesForInstances(store, instance.id)
                val blocker =
                    queue.enqueue("blocker", resources) {
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                ready.await()
                val cancelled =
                    queue.enqueue(
                        "queued",
                        resources,
                        prepare = { NewInstallationCleanup(store).register(instance, it) },
                    ) {
                        error("Cancelled queued installation must not run")
                    }
                queue.cancel(cancelled)
                withTimeout(5000) {
                    while (
                        store.state.value.jobs.first { it.id == cancelled }.status !=
                            JobStatus.Cancelled
                    ) delay(20)
                }
                assertFalse(folder.exists())
                assertTrue(store.data.instances.none { it.id == instance.id })
                queue.cancel(blocker)
                queue.awaitIdle()
            }
            scope.cancel()
        }

    @Test
    fun `real installation cancellation deletes downloads outputs and directories but retains referenced files`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
                val jar = jarBytes()
                val asset = ByteArray(4096) { 7 }
                val assetHash = sha1(asset)
                val index =
                    gson.toJson(
                        mapOf(
                            "objects" to
                                mapOf("asset" to mapOf("hash" to assetHash, "size" to asset.size))
                        )
                    )
                fun library(name: String, path: String) =
                    mapOf(
                        "name" to name,
                        "downloads" to
                            mapOf(
                                "artifact" to
                                    mapOf(
                                        "url" to "$base/library",
                                        "path" to path,
                                        "sha1" to sha1(jar),
                                        "size" to jar.size,
                                    )
                            ),
                    )
                val shared = "fixture/shared/1/shared-1.jar"
                val fresh = "fixture/fresh/1/fresh-1.jar"
                val profile =
                    gson.toJson(
                        mapOf(
                            "id" to "1.20.1",
                            "mainClass" to "fixture.Main",
                            "libraries" to
                                listOf(
                                    library("fixture:shared:1", shared),
                                    library("fixture:fresh:1", fresh),
                                ),
                            "downloads" to
                                mapOf(
                                    "client" to
                                        mapOf(
                                            "url" to "$base/client",
                                            "sha1" to sha1(jar),
                                            "size" to jar.size,
                                        )
                                ),
                            "assetIndex" to
                                mapOf(
                                    "id" to "cancel-assets",
                                    "url" to "$base/index",
                                    "sha1" to sha1(index.toByteArray()),
                                ),
                        )
                    )
                val manifest =
                    gson.toJson(
                        mapOf(
                            "versions" to
                                listOf(
                                    mapOf(
                                        "id" to "1.20.1",
                                        "type" to "release",
                                        "url" to "$base/profile",
                                        "sha1" to sha1(profile.toByteArray()),
                                    )
                                )
                        )
                    )
                val assetStarted = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest" -> MockResponse().setBody(manifest)
                                "/profile" -> MockResponse().setBody(profile)
                                "/client",
                                "/library" -> MockResponse().setBody(Buffer().write(jar))
                                "/index" -> MockResponse().setBody(index)
                                "/${assetHash.take(2)}/$assetHash" -> {
                                    assetStarted.countDown()
                                    MockResponse()
                                        .setBody(Buffer().write(asset))
                                        .throttleBody(1, 1, TimeUnit.SECONDS)
                                }
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
                val root = store.minecraftRoot
                val other =
                    GameInstance(
                        name = "Existing",
                        gameVersion = "old",
                        versionId = "old",
                        gameDirectoryName = "old",
                        installed = true,
                    )
                writeJson(
                    root.resolve("versions/old/old.json"),
                    mapOf(
                        "id" to "old",
                        "mainClass" to "fixture.Main",
                        "libraries" to listOf(library("fixture:shared:1", shared)),
                    ),
                )
                val existing = root.resolve("versions/old/old.jar").apply { writeBytes(jar) }
                store.mutate { it.copy(instances = listOf(other)) }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                NetworkClient(source = { "official" }).use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            "$base/manifest",
                            base,
                        )
                    val instance =
                        installer.createInstance(InstallRequest("New cancelled game", "1.20.1"))
                    val queue = JobQueue(store, scope)
                    val generated = root.resolve("libraries/processor/output.jar")
                    val id =
                        queue.enqueue(
                            "install",
                            taskResourcesForInstances(store, instance.id),
                            prepare = { NewInstallationCleanup(store).register(instance, it) },
                        ) {
                            writeOutput(generated) {
                                generated.parent.createDirectories()
                                generated.writeBytes(jar)
                            }
                            installer.install(instance.id, this)
                        }
                    try {
                        assertTrue(assetStarted.await(10, TimeUnit.SECONDS))
                        val partial =
                            network.partialFile(
                                root.resolve("assets/objects/${assetHash.take(2)}/$assetHash")
                            )
                        withTimeout(3000) { while (!partial.exists()) delay(20) }
                        assertTrue(root.resolve("versions/1.20.1/1.20.1.jar").exists())
                        assertTrue(root.resolve("libraries/$fresh").exists())
                        queue.cancel(id)
                        withTimeout(5000) { queue.awaitIdle() }
                        assertEquals(
                            JobStatus.Cancelled,
                            store.state.value.jobs.first { it.id == id }.status,
                        )
                        assertFalse(store.versionGamePath(instance).exists())
                        assertFalse(root.resolve("versions/1.20.1").exists())
                        assertFalse(root.resolve("libraries/fixture/fresh").exists())
                        assertFalse(generated.parent.exists())
                        assertFalse(root.resolve("assets/indexes/cancel-assets.json").exists())
                        assertFalse(partial.exists())
                        assertFalse(root.resolve("assets/objects/${assetHash.take(2)}").exists())
                        assertTrue(store.data.instances.none { it.id == instance.id })
                        assertContentEquals(jar, existing.readBytes())
                        assertContentEquals(jar, root.resolve("libraries/$shared").readBytes())
                    } finally {
                        queue.cancelAll()
                        queue.awaitIdle()
                        scope.cancel()
                    }
                }
            }
        }

    @Test
    fun `cancelling new pack installation removes its owned copy and keeps the source archive`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            NetworkClient().use { network ->
                val installer = MinecraftInstaller(store, network, JavaService(store, network))
                val instance = installer.createInstance(InstallRequest("Pack", "1.20.1"))
                val source = directory.resolve("source.mrpack").apply { writeText("user source") }
                val copy = store.directories.packArchives.resolve("${instance.id}.mrpack")
                copy.parent.createDirectories()
                copy.writeText("owned copy")
                store.updateInstance(instance.id) { it.copy(packFile = copy.toString()) }
                val queue = JobQueue(store, scope)
                val ready = CompletableDeferred<Unit>()
                val id =
                    queue.enqueue(
                        "pack",
                        taskResourcesForInstances(store, instance.id),
                        prepare = { NewInstallationCleanup(store).register(instance, it) },
                    ) {
                        ready.complete(Unit)
                        awaitCancellation()
                    }
                ready.await()
                queue.cancel(id)
                queue.awaitIdle()
                assertFalse(copy.exists())
                assertEquals("user source", source.readText())
                assertFalse(store.versionGamePath(instance).exists())
            }
            scope.cancel()
        }

    private fun jarBytes(): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("fixture/Main.class"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
            .toByteArray()
}
