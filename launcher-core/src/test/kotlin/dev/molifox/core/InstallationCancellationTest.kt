// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用实际 HTTP 文件和任务队列验证取消后的版本、下载和共享文件清理。

package dev.molifox.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** fixture 版本引用的共享库与全新库相对路径；JSON 与断言共用同一份。 */
private const val SHARED_LIBRARY_PATH = "fixture/shared/1/shared-1.jar"

private const val FRESH_LIBRARY_PATH = "fixture/fresh/1/fresh-1.jar"

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
                withTimeout(5000) { awaitJobStatus(store, cancelled, JobStatus.Cancelled) }
                assertFalse(folder.exists())
                assertTrue(store.data.instances.none { it.id == instance.id })
                queue.cancel(blocker)
                queue.awaitIdle()
            }
            scope.cancel()
        }

    @Test
    fun `cancelling installation cleans outputs and retains referenced files`() =
        runBlocking {
            MockWebServer().use { server ->
                val fixture = startFixtureServer(server)
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
                val root = store.minecraftRoot
                val existing = prepareSharedInstance(store, root, fixture)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                NetworkClient(source = { "official" }).use { network ->
                    val installation = startInstallation(store, network, scope, fixture, root)
                    try {
                        val partial = awaitAssetDownload(network, root, fixture)
                        assertInstallStarted(root)
                        installation.queue.cancel(installation.id)
                        withTimeout(5000) { installation.queue.awaitIdle() }
                        assertJobStatus(store, installation.id, JobStatus.Cancelled)
                        assertInstallOutputsRemoved(store, installation, root)
                        assertDownloadArtifactsRemoved(root, partial, fixture.assetHash)
                        assertReferencedFilesKept(store, installation, root, fixture, existing)
                    } finally {
                        installation.queue.cancelAll()
                        installation.queue.awaitIdle()
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

    /** fixture 服务器要回应的全部正文；[dispatcher] 逐路径返回，资源对象带限速。 */
    private class FixturePayloads(
        val manifest: String,
        val profile: String,
        val index: String,
        val jar: ByteArray,
        val asset: ByteArray,
        val assetHash: String,
    ) {
        fun dispatcher(assetStarted: CountDownLatch): Dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path) {
                        "/manifest" -> MockResponse().setBody(manifest)
                        "/profile" -> MockResponse().setBody(profile)
                        "/client",
                        "/library",
                        -> MockResponse().setBody(Buffer().write(jar))
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
    }

    /** 取消安装用的固定值：服务器地址、客户端 jar、资源对象哈希与「资源开始下载」信号。 */
    private inner class CancellationFixture(
        val base: String,
        val jar: ByteArray,
        val assetHash: String,
        val assetStarted: CountDownLatch,
    ) {
        /** 与 fixture 服务器匹配的 library 条目。 */
        fun library(
            name: String,
            path: String,
        ): Map<String, Any> = libraryEntry(base, jar, name, path)
    }

    /** 一次排队中的隔离安装：实例、队列、写入中的产物路径与任务 id。 */
    private class InstallationUnderTest(
        val instance: GameInstance,
        val queue: JobQueue,
        val generated: Path,
        val id: String,
    )

    /** 资源索引：只列出一个测试资源对象。 */
    private fun assetIndexJson(
        hash: String,
        size: Int,
    ): String =
        gson.toJson(
            mapOf(
                "objects" to
                    mapOf("asset" to mapOf("hash" to hash, "size" to size)),
            ),
        )

    /** 版本 JSON：两个 library 与 client 都指向 fixture 服务器。 */
    private fun versionProfileJson(
        base: String,
        jar: ByteArray,
        index: String,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "1.20.1",
                "mainClass" to "fixture.Main",
                "libraries" to
                    listOf(
                        libraryEntry(base, jar, "fixture:shared:1", SHARED_LIBRARY_PATH),
                        libraryEntry(base, jar, "fixture:fresh:1", FRESH_LIBRARY_PATH),
                    ),
                "downloads" to
                    mapOf(
                        "client" to
                            mapOf(
                                "url" to "$base/client",
                                "sha1" to sha1(jar),
                                "size" to jar.size,
                            ),
                    ),
                "assetIndex" to
                    mapOf(
                        "id" to "cancel-assets",
                        "url" to "$base/index",
                        "sha1" to sha1(index.toByteArray()),
                    ),
            ),
        )

    /** 版本清单：只有一个 release 版本，指向 fixture 服务器。 */
    private fun versionManifestJson(
        base: String,
        profile: String,
    ): String =
        gson.toJson(
            mapOf(
                "versions" to
                    listOf(
                        mapOf(
                            "id" to "1.20.1",
                            "type" to "release",
                            "url" to "$base/profile",
                            "sha1" to sha1(profile.toByteArray()),
                        ),
                    ),
            ),
        )

    /** 与 fixture 服务器匹配的 library 条目；[path] 是库在游戏目录里的相对路径。 */
    private fun libraryEntry(
        base: String,
        jar: ByteArray,
        name: String,
        path: String,
    ): Map<String, Any> =
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
                        ),
                ),
        )

    /** 启动本地 fixture 服务器：版本清单、客户端 jar 与一个限速的资源对象。 */
    private fun startFixtureServer(server: MockWebServer): CancellationFixture {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val base = server.loopbackUrl("/").toString().removeSuffix("/")
        val jar = jarBytes()
        val asset = ByteArray(4096) { 7 }
        val assetHash = sha1(asset)
        val assetStarted = CountDownLatch(1)
        val index = assetIndexJson(assetHash, asset.size)
        val profile = versionProfileJson(base, jar, index)
        val manifest = versionManifestJson(base, profile)
        server.dispatcher =
            FixturePayloads(manifest, profile, index, jar, asset, assetHash)
                .dispatcher(assetStarted)
        return CancellationFixture(base, jar, assetHash, assetStarted)
    }

    /** 已有实例：写一份引用同一个共享 library 的版本 JSON 与客户端 jar。 */
    private fun prepareSharedInstance(
        store: LauncherStore,
        root: Path,
        fixture: CancellationFixture,
    ): Path {
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
                "libraries" to
                    listOf(fixture.library("fixture:shared:1", SHARED_LIBRARY_PATH)),
            ),
        )
        val existing = root.resolve("versions/old/old.jar").apply { writeBytes(fixture.jar) }
        store.mutate { it.copy(instances = listOf(other)) }
        return existing
    }

    /** 排队一次隔离安装：先写产物再安装，便于取消后断言产物被清理。 */
    private fun startInstallation(
        store: LauncherStore,
        network: NetworkClient,
        scope: CoroutineScope,
        fixture: CancellationFixture,
        root: Path,
    ): InstallationUnderTest {
        val installer =
            MinecraftInstaller(
                store,
                network,
                JavaService(store, network),
                "${fixture.base}/manifest",
                fixture.base,
            )
        val instance = installer.createInstance(InstallRequest("New cancelled game", "1.20.1"))
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
                    generated.writeBytes(fixture.jar)
                }
                installer.install(instance.id, this)
            }
        return InstallationUnderTest(instance, queue, generated, id)
    }

    /** 等资源对象开始下载并留下 .part 文件，确认取消发生在下载途中。 */
    private suspend fun awaitAssetDownload(
        network: NetworkClient,
        root: Path,
        fixture: CancellationFixture,
    ): Path {
        assertTrue(fixture.assetStarted.await(10, TimeUnit.SECONDS))
        val partial = network.partialFile(assetObjectPath(root, fixture.assetHash))
        withTimeout(3000) { while (!partial.exists()) delay(20) }
        return partial
    }

    /** 资源对象在游戏目录里的位置：`assets/objects/<前两位>/<完整哈希>`。 */
    private fun assetObjectPath(
        root: Path,
        hash: String,
    ): Path = root.resolve("assets/objects/${hash.take(2)}/$hash")

    /** 安装已经开始：版本 jar 与新的库文件都已落盘。 */
    private fun assertInstallStarted(root: Path) {
        assertTrue(root.resolve("versions/1.20.1/1.20.1.jar").exists())
        assertTrue(root.resolve("libraries/$FRESH_LIBRARY_PATH").exists())
    }

    /** 等待任务到达 [expected] 状态；超时由调用方的 [withTimeout] 报告。 */
    private suspend fun awaitJobStatus(
        store: LauncherStore,
        jobId: String,
        expected: JobStatus,
    ) {
        while (store.state.value.jobs
                .first { it.id == jobId }
                .status != expected
        ) {
            delay(20)
        }
    }

    /** 断言任务已经是 [expected] 状态。 */
    private fun assertJobStatus(
        store: LauncherStore,
        jobId: String,
        expected: JobStatus,
    ) {
        assertEquals(
            expected,
            store.state.value.jobs
                .first { it.id == jobId }
                .status,
        )
    }

    /** 取消后实例记录、版本目录、生成的库与资源索引都不该留下。 */
    private fun assertInstallOutputsRemoved(
        store: LauncherStore,
        installation: InstallationUnderTest,
        root: Path,
    ) {
        assertFalse(store.versionGamePath(installation.instance).exists())
        assertFalse(root.resolve("versions/1.20.1").exists())
        assertFalse(root.resolve("libraries/fixture/fresh").exists())
        assertFalse(installation.generated.parent.exists())
        assertFalse(root.resolve("assets/indexes/cancel-assets.json").exists())
    }

    /** 取消后 .part 下载与资源对象目录都不该留下。 */
    private fun assertDownloadArtifactsRemoved(
        root: Path,
        partial: Path,
        assetHash: String,
    ) {
        assertFalse(partial.exists())
        assertFalse(root.resolve("assets/objects/${assetHash.take(2)}").exists())
    }

    /** 仍被已有实例引用的共享文件与旧版本 jar 必须原样保留。 */
    private fun assertReferencedFilesKept(
        store: LauncherStore,
        installation: InstallationUnderTest,
        root: Path,
        fixture: CancellationFixture,
        existing: Path,
    ) {
        assertTrue(store.data.instances.none { it.id == installation.instance.id })
        assertContentEquals(fixture.jar, existing.readBytes())
        assertContentEquals(
            fixture.jar,
            root.resolve("libraries/$SHARED_LIBRARY_PATH").readBytes(),
        )
    }

    private fun jarBytes(): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    zip.putNextEntry(ZipEntry("fixture/Main.class"))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }.toByteArray()
}
