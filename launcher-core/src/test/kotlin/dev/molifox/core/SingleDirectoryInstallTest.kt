// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证安装加载器只发布一个自包含版本目录，保留既有基础版本目录，并复用统一的回收路径。

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

class SingleDirectoryInstallTest {
    @TempDir lateinit var directory: Path

    private val gameVersion = "1.20.1"
    private val loaderVersion = "0.15.0"
    private val loaderLibraryPath = "net/fabricmc/fabric-loader/0.15.0/fabric-loader-0.15.0.jar"

    private fun jarBytes(entry: String): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
            .toByteArray()

    private fun baseUrl(server: MockWebServer): String =
        server.loopbackUrl("/").toString().removeSuffix("/")

    private fun baseProfile(base: String, clientJar: ByteArray, baseLibrary: ByteArray): String {
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
                "downloads" to
                    mapOf(
                        "client" to
                            mapOf(
                                "url" to "$base/client.jar",
                                "sha1" to sha1(clientJar),
                                "size" to clientJar.size,
                            )
                    ),
                "assetIndex" to
                    mapOf(
                        "id" to "fixture-index",
                        "url" to "$base/index.json",
                        "sha1" to sha1(index),
                    ),
                "logging" to
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
                            )
                    ),
                "libraries" to
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
                                        )
                                ),
                        )
                    ),
            )
        )
    }

    private fun loaderProfile(base: String, loaderLibrary: ByteArray): String =
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
                                        )
                                ),
                        )
                    ),
            )
        )

    /** 资源索引必须和它声明的 sha1 用同一份字节，否则下载校验会失败。 */
    private fun assetsIndex(): String = gson.toJson(mapOf("objects" to emptyMap<String, Any>()))

    private fun manifest(base: String, profile: String): String =
        gson.toJson(
            mapOf(
                "versions" to
                    listOf(
                        mapOf(
                            "id" to gameVersion,
                            "type" to "release",
                            "url" to "$base/version.json",
                            "sha1" to sha1(profile.toByteArray()),
                        )
                    )
            )
        )

    private fun installer(server: MockWebServer, store: LauncherStore, network: NetworkClient) =
        MinecraftInstaller(
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
            if (!versions.isDirectory()) emptyList() else versions.listDirectoryEntries().map { it.name }.sorted()
        }

    @Test
    fun `a loader on a fresh base publishes one self-contained directory named after the instance`() =
        runBlocking {
            val clientJar = jarBytes("fixture/Main.class")
            val baseLibrary = jarBytes("fixture/Base.class")
            val loaderLibrary = jarBytes("fabric/Loader.class")
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val base = baseUrl(server)
                val profile = baseProfile(base, clientJar, baseLibrary)
                val loader = loaderProfile(base, loaderLibrary)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest.json" -> MockResponse().setBody(manifest(base, profile))
                                "/version.json" -> MockResponse().setBody(profile)
                                "/client.jar" -> MockResponse().setBody(Buffer().write(clientJar))
                                "/base-library.jar" ->
                                    MockResponse().setBody(Buffer().write(baseLibrary))
                                "/loader-library.jar" ->
                                    MockResponse().setBody(Buffer().write(loaderLibrary))
                                "/index.json" -> MockResponse().setBody(assetsIndex())
                                "/log.xml" -> MockResponse().setBody("<configuration/>")
                                "/v2/versions/loader/$gameVersion/$loaderVersion/profile/json" ->
                                    MockResponse().setBody(loader)
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                val root = store.minecraftRoot
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(server, store, network)
                    val instance =
                        installer.createInstance(
                            InstallRequest("forge26.3测试", gameVersion, LoaderType.Fabric, loaderVersion)
                        )
                    installer.install(instance.id, JobContext(store, "install"))
                    val installed = store.instance(instance.id)
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
                    assertContentEquals(clientJar, folder.resolve("forge26.3测试.jar").readBytes())
                    // 目录里配置自包含：按实例名解析就能拿到主类、JAR 与加载器库。
                    val resolved = VersionResolver(root, store.platform).resolve(installed.versionId)
                    assertEquals("forge26.3测试", resolved.jarId)
                    assertEquals("fixture.KnotClient", resolved.mainClass)
                    assertTrue(
                        root.resolve("libraries/$loaderLibraryPath").isRegularFile(),
                        "加载器依赖应该补齐到共享库目录",
                    )
                }
            }
        }

    @Test
    fun `a loader on a pre-existing base keeps that directory and inherits from it`() = runBlocking {
        val clientJar = jarBytes("fixture/Main.class")
        val baseLibrary = jarBytes("fixture/Base.class")
        val loaderLibrary = jarBytes("fabric/Loader.class")
        MockWebServer().use { server ->
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val base = baseUrl(server)
            val profile = baseProfile(base, clientJar, baseLibrary)
            val loader = loaderProfile(base, loaderLibrary)
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.path) {
                            "/manifest.json" -> MockResponse().setBody(manifest(base, profile))
                            "/version.json" -> MockResponse().setBody(profile)
                            "/client.jar" -> MockResponse().setBody(Buffer().write(clientJar))
                            "/base-library.jar" -> MockResponse().setBody(Buffer().write(baseLibrary))
                            "/loader-library.jar" ->
                                MockResponse().setBody(Buffer().write(loaderLibrary))
                            "/index.json" -> MockResponse().setBody(assetsIndex())
                            "/log.xml" -> MockResponse().setBody("<configuration/>")
                            "/v2/versions/loader/$gameVersion/$loaderVersion/profile/json" ->
                                MockResponse().setBody(loader)
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
            val root = store.minecraftRoot
            // 用户此前装过原版：基础版本目录本来就完整存在。
            val baseFolder = root.resolve("versions/$gameVersion").apply { createDirectories() }
            writeJson(baseFolder.resolve("$gameVersion.json"), gson.fromJson(profile, Map::class.java))
            baseFolder.resolve("$gameVersion.jar").writeBytes(clientJar)
            NetworkClient(source = { "official" }).use { network ->
                val installer = installer(server, store, network)
                val instance =
                    installer.createInstance(
                        InstallRequest("我的整合包", gameVersion, LoaderType.Fabric, loaderVersion)
                    )
                installer.install(instance.id, JobContext(store, "install"))
                val installed = store.instance(instance.id)
                assertEquals("我的整合包", installed.versionId)
                assertEquals(
                    listOf("1.20.1", "我的整合包"),
                    versionDirectories(root),
                )
                assertTrue(baseFolder.resolve("$gameVersion.json").isRegularFile())
                assertContentEquals(clientJar, baseFolder.resolve("$gameVersion.jar").readBytes())
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
        }
    }

    @Test
    fun `a cancelled install reclaims the intermediate version directories it created`() =
        runBlocking {
            val clientJar = jarBytes("fixture/Main.class")
            val baseLibrary = jarBytes("fixture/Base.class")
            val loaderLibrary = ByteArray(64) { 9 }
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val base = baseUrl(server)
                val profile = baseProfile(base, clientJar, baseLibrary)
                val loader = loaderProfile(base, loaderLibrary)
                val loaderStarted = CountDownLatch(1)
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when (request.path) {
                                "/manifest.json" -> MockResponse().setBody(manifest(base, profile))
                                "/version.json" -> MockResponse().setBody(profile)
                                "/client.jar" -> MockResponse().setBody(Buffer().write(clientJar))
                                "/base-library.jar" ->
                                    MockResponse().setBody(Buffer().write(baseLibrary))
                                "/loader-library.jar" -> {
                                    // 目标目录和基础目录此时都已存在，取消必须两边都不留残留。
                                    loaderStarted.countDown()
                                    MockResponse()
                                        .setBody(Buffer().write(loaderLibrary))
                                        .throttleBody(1, 1, TimeUnit.SECONDS)
                                }
                                "/index.json" -> MockResponse().setBody(assetsIndex())
                                "/log.xml" -> MockResponse().setBody("<configuration/>")
                                "/v2/versions/loader/$gameVersion/$loaderVersion/profile/json" ->
                                    MockResponse().setBody(loader)
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
                store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
                val root = store.minecraftRoot
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                NetworkClient(source = { "official" }).use { network ->
                    val installer = installer(server, store, network)
                    val instance =
                        installer.createInstance(
                            InstallRequest("取消的安装", gameVersion, LoaderType.Fabric, loaderVersion)
                        )
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
                                store.state.value.jobs.first { it.id == id }.status !=
                                    JobStatus.Cancelled
                            )
                                delay(20)
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
            store.mutate { data -> data.copy(instances = data.instances.filter { it.id != first.id }) }
            root.resolve("versions/占用的名字").createDirectories()
            assertFailsWith<IllegalArgumentException> {
                installer.createInstance(InstallRequest("占用的名字", gameVersion))
            }
            assertEquals(listOf("26.3 测试", "占用的名字"), versionDirectories(root))
            assertTrue(store.data.instances.isEmpty())
        }
    }
}