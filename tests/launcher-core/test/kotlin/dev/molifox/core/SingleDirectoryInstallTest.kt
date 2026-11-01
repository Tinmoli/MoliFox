// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证安装加载器只发布一个自包含版本目录，保留既有基础版本目录，并复用统一的回收路径。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
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
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleDirectoryInstallTest {
    @TempDir lateinit var directory: Path

    private val gameVersion = "1.20.1"
    private val loaderVersion = "0.15.0"
    private val loaderLibraryPath = "net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar"

    private fun baseProfile(
        base: String,
        clientJar: ByteArray,
        baseLibrary: ByteArray,
    ): String {
        val index = assetsIndex().toByteArray()
        val logConfig = "<configuration/>".toByteArray()
        return gson.toJson(
            mapOf(
                "id" to gameVersion,
                "type" to "release",
                "mainClass" to "fixture.Main",
                "javaVersion" to mapOf("majorVersion" to 17),
                "arguments" to
                    mapOf(
                        "game" to listOf("--username", "\${auth_player_name}"),
                        "jvm" to listOf("-Dfixture.base=true"),
                    ),
                "downloads" to baseClientDownload(base, clientJar),
                "assetIndex" to baseAssetIndex(base, index),
                "logging" to baseLogging(base, logConfig),
                "libraries" to baseLibraries(base, baseLibrary),
            ),
        )
    }

    private fun loaderProfile(
        base: String,
        loaderLibrary: ByteArray,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "fabric-loader-$loaderVersion-$gameVersion",
                "inheritsFrom" to gameVersion,
                "mainClass" to "fixture.KnotClient",
                "arguments" to mapOf("game" to emptyList<Any>(), "jvm" to emptyList<Any>()),
                "libraries" to
                    listOf(
                        mapOf(
                            "name" to "net.fabricmc:fabric-loader:$loaderVersion",
                            "downloads" to
                                mapOf(
                                    "artifact" to
                                        mapOf(
                                            "url" to "$base/loader-library.jar",
                                            "path" to loaderLibraryPath,
                                            "sha1" to sha1(loaderLibrary),
                                            "size" to loaderLibrary.size,
                                        ),
                                ),
                        ),
                    ),
            ),
        )

    private fun manifest(
        base: String,
        profile: String,
    ): String =
        gson.toJson(
            mapOf(
                "versions" to
                    listOf(
                        mapOf(
                            "id" to gameVersion,
                            "type" to "release",
                            "url" to "$base/version.json",
                            "sha1" to sha1(profile.toByteArray()),
                        ),
                    ),
            ),
        )

    /** 固定的夹具响应；`loaderLibraryResponse` 为空时按普通下载返回加载器库。 */
    private fun fixtureDispatcher(
        base: String,
        profile: String,
        loader: String,
        jars: InstallJars,
        loaderLibraryResponse: (() -> MockResponse)? = null,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(manifest(base, profile))
                    "/version.json" -> MockResponse().setBody(profile)
                    "/client.jar" -> MockResponse().setBody(Buffer().write(jars.clientJar))
                    "/base-library.jar" ->
                        MockResponse().setBody(Buffer().write(jars.baseLibrary))
                    "/loader-library.jar" ->
                        loaderLibraryResponse?.invoke()
                            ?: MockResponse().setBody(Buffer().write(jars.loaderLibrary))
                    "/index.json" -> MockResponse().setBody(assetsIndex())
                    "/log.xml" -> MockResponse().setBody("<configuration/>")
                    "/v2/versions/loader/$gameVersion/$loaderVersion/profile/json" ->
                        MockResponse().setBody(loader)
                    else -> MockResponse().setResponseCode(404)
                }
        }

    /** 启动固定响应的 MockWebServer，并在它关闭前执行 block。 */
    private suspend fun <T> withFixtureServer(
        jars: InstallJars,
        loaderLibraryResponse: (() -> MockResponse)? = null,
        block: suspend (FixtureServer) -> T,
    ): T =
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val base = baseUrl(server)
            val profile = baseProfile(base, jars.clientJar, jars.baseLibrary)
            val loader = loaderProfile(base, jars.loaderLibrary)
            server.dispatcher =
                fixtureDispatcher(base, profile, loader, jars, loaderLibraryResponse)
            block(FixtureServer(server, profile))
        }

    /** 预置用户此前装好的原版目录：版本 JSON 与客户端 JAR 都在。 */
    private fun writeExistingBase(
        root: Path,
        profile: String,
        clientJar: ByteArray,
    ): Path {
        val baseFolder = root.resolve("versions/$gameVersion").apply { createDirectories() }
        writeJson(baseFolder.resolve("$gameVersion.json"), gson.fromJson(profile, Map::class.java))
        baseFolder.resolve("$gameVersion.jar").writeBytes(clientJar)
        return baseFolder
    }

    /** 新装加载器只留实例自己的目录，版本 JSON 自包含且能按实例名解析。 */
    private fun assertFreshLoaderPublished(
        store: LauncherStore,
        root: Path,
        installed: GameInstance,
        jars: InstallJars,
    ) {
        assertEquals("forge26.3测试", installed.versionId)
        assertEquals("forge26.3测试", installed.gameDirectoryName)
        assertTrue(installed.installed)
        // 只留下实例自己的目录：基础版本与自动生成的加载器目录都不再常驻。
        assertEquals(listOf("forge26.3测试"), versionDirectories(root))
        val folder = root.resolve("versions/forge26.3测试")
        val json = readObject(folder.resolve("forge26.3测试.json"))
        assertEquals("forge26.3测试", json.string("id"))
        assertEquals("", json.string("inheritsFrom"))
        assertEquals("fixture.KnotClient", json.string("mainClass"))
        assertEquals(gameVersion, json.string("_minecraftVersion"))
        assertTrue(json.obj("downloads").has("client"))
        assertTrue(json.has("assetIndex"))
        assertEquals("fixture-index", json.obj("assetIndex").string("id"))
        assertTrue(json.has("logging"))
        assertEquals(2, json.array("libraries").size())
        assertEquals(
            listOf("--username", "\${auth_player_name}"),
            json.obj("arguments").array("game").map { it.asString },
        )
        assertContentEquals(jars.clientJar, folder.resolve("forge26.3测试.jar").readBytes())
        // 目录里配置自包含：按实例名解析就能拿到主类、JAR 与加载器库。
        val resolved = VersionResolver(root, store.platform).resolve(installed.versionId)
        assertEquals("forge26.3测试", resolved.jarId)
        assertEquals("fixture.KnotClient", resolved.mainClass)
        assertTrue(
            root.resolve("libraries/$loaderLibraryPath").isRegularFile(),
            "加载器依赖应该补齐到共享库目录",
        )
    }

    /** 已有基础版本时：两个目录并存，加载器 JSON 继承基础版本且不自带客户端 JAR。 */
    private fun assertLoaderInheritsBase(
        store: LauncherStore,
        root: Path,
        installed: GameInstance,
        baseFolder: Path,
        clientJar: ByteArray,
    ) {
        assertEquals("我的整合包", installed.versionId)
        assertEquals(
            listOf("1.20.1", "我的整合包"),
            versionDirectories(root),
        )
        assertTrue(baseFolder.resolve("$gameVersion.json").isRegularFile())
        assertContentEquals(
            clientJar,
            baseFolder.resolve("$gameVersion.jar").readBytes(),
        )
        val json = readObject(root.resolve("versions/我的整合包/我的整合包.json"))
        assertEquals("我的整合包", json.string("id"))
        assertEquals(gameVersion, json.string("inheritsFrom"))
        assertEquals("fixture.KnotClient", json.string("mainClass"))
        // 不能一边写 inheritsFrom 一边声明自己的客户端 JAR。
        assertFalse(json.has("downloads"))
        assertFalse(root.resolve("versions/我的整合包/我的整合包.jar").exists())
        val resolved = VersionResolver(root, store.platform).resolve(installed.versionId)
        assertEquals(gameVersion, resolved.jarId)
        assertEquals("fixture.KnotClient", resolved.mainClass)
    }

    /** 取消安装后：实例目录、基础版本目录与中间目录都被回收，实例记录也回滚。 */
    private suspend fun assertCancelledInstallReclaimed(
        store: LauncherStore,
        installer: MinecraftInstaller,
        instance: GameInstance,
        root: Path,
        loaderStarted: CountDownLatch,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val queue = JobQueue(store, scope)
        try {
            val id =
                queue.enqueue(
                    "install",
                    taskResourcesForInstances(store, instance.id),
                    prepare = { NewInstallationCleanup(store).register(instance, it) },
                ) {
                    installer.install(instance.id, this)
                }
            assertTrue(loaderStarted.await(20, TimeUnit.SECONDS))
            assertTrue(store.versionGamePath(instance).isDirectory())
            assertTrue(root.resolve("versions/$gameVersion").isDirectory())
            queue.cancel(id)
            withTimeout(20_000) {
                while (
                    store.state.value.jobs
                        .first { it.id == id }
                        .status !=
                    JobStatus.Cancelled
                ) {
                    delay(20)
                }
            }
            withTimeout(20_000) { queue.awaitIdle() }
            assertFalse(store.versionGamePath(instance).exists())
            assertFalse(root.resolve("versions/$gameVersion").exists())
            assertTrue(versionDirectories(root).isEmpty())
            assertTrue(store.data.instances.none { it.id == instance.id })
        } finally {
            queue.cancelAll()
            runCatching { queue.awaitIdle() }
            scope.cancel()
        }
    }

    @Test
    fun `a fresh loader install publishes one directory named after the instance`() =
        runBlocking {
            val jars = installJars()
            withFixtureServer(jars) { fixture ->
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                val root = store.minecraftRoot
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(fixture.server, store, network)
                    val instance =
                        installer.createInstance(
                            InstallRequest(
                                "forge26.3测试",
                                gameVersion,
                                LoaderType.Fabric,
                                loaderVersion,
                            ),
                        )
                    installer.install(instance.id, JobContext(store, "install"))
                    assertFreshLoaderPublished(store, root, store.instance(instance.id), jars)
                }
            }
        }

    @Test
    fun `a loader on a pre-existing base keeps that directory and inherits from it`() =
        runBlocking {
            val jars = installJars()
            withFixtureServer(jars) { fixture ->
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                val root = store.minecraftRoot
                // 用户此前装过原版：基础版本目录本来就完整存在。
                val baseFolder = writeExistingBase(root, fixture.profile, jars.clientJar)
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(fixture.server, store, network)
                    val instance =
                        installer.createInstance(
                            InstallRequest("我的整合包", gameVersion, LoaderType.Fabric, loaderVersion),
                        )
                    installer.install(instance.id, JobContext(store, "install"))
                    assertLoaderInheritsBase(
                        store,
                        root,
                        store.instance(instance.id),
                        baseFolder,
                        jars.clientJar,
                    )
                }
            }
        }

    @Test
    fun `a cancelled install reclaims the intermediate version directories it created`() =
        runBlocking {
            val jars = installJars(ByteArray(64) { 9 })
            val loaderStarted = CountDownLatch(1)
            val loaderLibraryResponse = {
                // 目标目录和基础目录此时都已存在，取消必须两边都不留残留。
                loaderStarted.countDown()
                MockResponse()
                    .setBody(Buffer().write(jars.loaderLibrary))
                    .throttleBody(1, 1, TimeUnit.SECONDS)
            }
            withFixtureServer(jars, loaderLibraryResponse) { fixture ->
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
                val root = store.minecraftRoot
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(fixture.server, store, network)
                    val instance =
                        installer.createInstance(
                            InstallRequest("取消的安装", gameVersion, LoaderType.Fabric, loaderVersion),
                        )
                    assertCancelledInstallReclaimed(store, installer, instance, root, loaderStarted)
                }
            }
        }

    @Test
    fun `a colliding instance name is rejected before anything is written`() {
        val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
        val root = store.minecraftRoot
        NetworkClient().use { network ->
            val installer = MinecraftInstaller(store, network, JavaService(store, network))
            val first = installer.createInstance(InstallRequest("26.3 测试", gameVersion))
            assertEquals("26.3 测试", first.gameDirectoryName)
            assertFailsWith<IllegalArgumentException> {
                installer.createInstance(InstallRequest("26.3 测试", gameVersion))
            }
            assertEquals(1, store.data.instances.size)
            assertEquals(listOf("26.3 测试"), versionDirectories(root))
            // 目录被别的来源占用时同样在写盘前拒绝，绝不另造一个「名字-2」目录。
            store.mutate { data ->
                data.copy(instances = data.instances.filter { it.id != first.id })
            }
            root.resolve("versions/占用的名字").createDirectories()
            assertFailsWith<IllegalArgumentException> {
                installer.createInstance(InstallRequest("占用的名字", gameVersion))
            }
            assertEquals(listOf("26.3 测试", "占用的名字"), versionDirectories(root))
            assertTrue(store.data.instances.isEmpty())
        }
    }
}

/** 三个夹具 JAR：客户端、原版依赖与加载器依赖。 */
private class InstallJars(
    val clientJar: ByteArray,
    val baseLibrary: ByteArray,
    val loaderLibrary: ByteArray,
)

/** 已启动的夹具服务器与它返回的原版版本 JSON。 */
private class FixtureServer(
    val server: MockWebServer,
    val profile: String,
)

private fun jarBytes(entry: String): ByteArray =
    ByteArrayOutputStream()
        .also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

private fun installJars(loaderLibrary: ByteArray = jarBytes("fabric/Loader.class")): InstallJars =
    InstallJars(
        jarBytes("fixture/Main.class"),
        jarBytes("fixture/Base.class"),
        loaderLibrary,
    )

private fun baseUrl(server: MockWebServer): String = server.loopbackUrl("/").toString().removeSuffix("/")

private fun installer(
    server: MockWebServer,
    store: LauncherStore,
    network: NetworkClient,
) = MinecraftInstaller(
    store,
    network,
    JavaService(store, network),
    "${baseUrl(server)}/manifest.json",
    baseUrl(server),
    fabricMetaBase = baseUrl(server),
    quiltMetaBase = baseUrl(server),
)

private fun versionDirectories(root: Path): List<String> =
    root.resolve("versions").let { versions ->
        if (!versions.isDirectory()) {
            emptyList()
        } else {
            versions
                .listDirectoryEntries()
                .map {
                    it.name
                }.sorted()
        }
    }

/** 资源索引必须和它声明的 sha1 用同一份字节，否则下载校验会失败。 */
private fun assetsIndex(): String = gson.toJson(mapOf("objects" to emptyMap<String, Any>()))

/** 原版清单里的客户端下载项。 */
private fun baseClientDownload(
    base: String,
    clientJar: ByteArray,
): Map<String, Any> =
    mapOf(
        "client" to
            mapOf(
                "url" to "$base/client.jar",
                "sha1" to sha1(clientJar),
                "size" to clientJar.size,
            ),
    )

/** 资源索引下载项；sha1 必须与 assetsIndex() 使用的字节一致。 */
private fun baseAssetIndex(
    base: String,
    index: ByteArray,
): Map<String, Any> =
    mapOf(
        "id" to "fixture-index",
        "url" to "$base/index.json",
        "sha1" to sha1(index),
    )

/** 日志配置下载项；sha1 必须与 /log.xml 返回的字节一致。 */
private fun baseLogging(
    base: String,
    logConfig: ByteArray,
): Map<String, Any> =
    mapOf(
        "client" to
            mapOf(
                "argument" to "-Dfixture.log=\${path}",
                "file" to
                    mapOf(
                        "id" to "fixture-log.xml",
                        "url" to "$base/log.xml",
                        "sha1" to sha1(logConfig),
                    ),
            ),
    )

/** 原版依赖库，用来验证共享库目录里的文件被补齐。 */
private fun baseLibraries(
    base: String,
    baseLibrary: ByteArray,
): List<Map<String, Any>> =
    listOf(
        mapOf(
            "name" to "fixture:base:1",
            "downloads" to
                mapOf(
                    "artifact" to
                        mapOf(
                            "url" to "$base/base-library.jar",
                            "path" to "fixture/base/1/base-1.jar",
                            "sha1" to sha1(baseLibrary),
                            "size" to baseLibrary.size,
                        ),
                ),
        ),
    )
