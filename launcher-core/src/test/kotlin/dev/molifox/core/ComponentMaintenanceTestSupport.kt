// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供组件维护测试的共享夹具：本地服务、版本配置与外部游戏根。

package dev.molifox.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/** 组件维护测试的公共夹具与断言：子类只写用例本身。 */
abstract class ComponentMaintenanceTestSupport {
    @TempDir
    lateinit var temporary: Path

    protected val gameVersion = "1.20.1"
    protected val oldLoader = "0.16.14"
    protected val newLoader = "0.17.0"

    protected class Fixture(
        val base: String,
        val profile: String,
        val clientJar: ByteArray,
        val baseLibrary: ByteArray,
        val oldLibrary: ByteArray,
        val newLibrary: ByteArray,
    )

    protected fun jarBytes(entry: String): ByteArray =
        ByteArrayOutputStream()
            .also { buffer ->
                ZipOutputStream(buffer).use { zip ->
                    zip.putNextEntry(ZipEntry(entry))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }.toByteArray()

    protected fun baseUrl(server: MockWebServer): String =
        server.loopbackUrl("/").toString().removeSuffix("/")

    protected fun fixture(server: MockWebServer): Fixture {
        val base = baseUrl(server)
        val clientJar = jarBytes("fixture/Main.class")
        val baseLibrary = jarBytes("fixture/Base.class")
        val oldLibrary = jarBytes("fabric/OldLoader.class")
        val newLibrary = jarBytes("fabric/NewLoader.class")
        val profile = fixtureProfile(base, clientJar, baseLibrary)
        return Fixture(base, profile, clientJar, baseLibrary, oldLibrary, newLibrary)
    }

    /** 基础版本配置：只声明客户端 JAR 与一个基础库。 */
    private fun fixtureProfile(
        base: String,
        clientJar: ByteArray,
        baseLibrary: ByteArray,
    ): String =
        gson.toJson(
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
                            ),
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
                                        ),
                                ),
                        ),
                    ),
            ),
        )

    protected fun loaderProfile(
        version: String,
        libraryUrl: String,
        loaderLibrary: ByteArray,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "fabric-loader-$version-$gameVersion",
                "inheritsFrom" to gameVersion,
                "mainClass" to "fixture.KnotClient",
                "arguments" to mapOf("game" to emptyList<Any>(), "jvm" to emptyList<Any>()),
                "libraries" to
                    listOf(
                        mapOf(
                            "name" to "net.fabricmc:fabric-loader:$version",
                            "downloads" to
                                mapOf(
                                    "artifact" to
                                        mapOf(
                                            "url" to libraryUrl,
                                            "path" to
                                                "net/fabricmc/fabric-loader/$version/" +
                                                "fabric-loader-$version.jar",
                                            "sha1" to sha1(loaderLibrary),
                                            "size" to loaderLibrary.size,
                                        ),
                                ),
                        ),
                    ),
            ),
        )

    protected fun manifest(fixture: Fixture): String =
        gson.toJson(
            mapOf(
                "versions" to
                    listOf(
                        mapOf(
                            "id" to gameVersion,
                            "type" to "release",
                            "url" to "${fixture.base}/version.json",
                            "sha1" to sha1(fixture.profile.toByteArray()),
                        ),
                    ),
            ),
        )

    /** 同一个本地服务既提供安装所需文件，也提供升级所需的新加载器配置；[newLibraryUrl] 可指向 404 制造失败。 */
    protected fun dispatcher(
        fixture: Fixture,
        newLibraryUrl: String? = null,
    ): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.path) {
                    "/manifest.json" -> MockResponse().setBody(manifest(fixture))
                    "/version.json" -> MockResponse().setBody(fixture.profile)
                    "/client.jar" -> MockResponse().setBody(Buffer().write(fixture.clientJar))
                    "/base-library.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.baseLibrary))
                    "/old-loader.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.oldLibrary))
                    "/new-loader.jar" ->
                        MockResponse().setBody(Buffer().write(fixture.newLibrary))
                    "/v2/versions/loader/$gameVersion/$oldLoader/profile/json" ->
                        MockResponse()
                            .setBody(
                                loaderProfile(
                                    oldLoader,
                                    "${fixture.base}/old-loader.jar",
                                    fixture.oldLibrary,
                                ),
                            )
                    "/v2/versions/loader/$gameVersion/$newLoader/profile/json" ->
                        MockResponse()
                            .setBody(
                                loaderProfile(
                                    newLoader,
                                    newLibraryUrl ?: "${fixture.base}/new-loader.jar",
                                    fixture.newLibrary,
                                ),
                            )
                    else -> MockResponse().setResponseCode(404)
                }
        }

    protected fun installer(
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
        forgeMavenBase = baseUrl(server),
    )

    /** 安装一个 Fabric 旧版本实例，返回实例与可复用的本地服务数据。 */
    protected suspend fun installOldFabricInstance(
        server: MockWebServer,
        store: LauncherStore,
        network: NetworkClient,
    ): Pair<GameInstance, Fixture> {
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val fixture = fixture(server)
        server.dispatcher = dispatcher(fixture)
        val installer = installer(server, store, network)
        val instance =
            installer.createInstance(
                InstallRequest("升级测试实例", gameVersion, LoaderType.Fabric, oldLoader),
            )
        installer.install(instance.id, JobContext(store, "install"))
        return store.instance(instance.id) to fixture
    }

    /** 在外部游戏根写一个基础版本和一个外部加载器版本（没有 molifoxManaged 标记）。 */
    protected fun writeExternalFabricVersion(
        root: Path,
        fixture: Fixture,
        loader: String,
        libraryUrl: String,
        library: ByteArray,
        parentPresent: Boolean = true,
    ): Path {
        if (parentPresent) {
            val base = root.resolve("versions/$gameVersion").createDirectories()
            base.resolve("$gameVersion.json").writeText(fixture.profile)
            base.resolve("$gameVersion.jar").writeBytes(fixture.clientJar)
        }
        val folder = root.resolve("versions/ForeignFabric").createDirectories()
        folder.resolve("ForeignFabric.json").writeText(
            foreignFabricProfile(loader, libraryUrl, library),
        )
        return folder
    }

    /** 外部加载器配置：带自定义字段、固定时间戳与旧版主类，用于验证合并时原样保留。 */
    private fun foreignFabricProfile(
        loader: String,
        libraryUrl: String,
        library: ByteArray,
    ): String =
        gson.toJson(
            mapOf(
                "id" to "ForeignFabric",
                "inheritsFrom" to gameVersion,
                "type" to "release",
                "time" to "2026-10-29T10:00:00Z",
                "mainClass" to "fixture.OldKnotClient",
                "foreignCustom" to mapOf("keep" to "yes"),
                "libraries" to
                    listOf(
                        mapOf(
                            "name" to "net.fabricmc:fabric-loader:$loader",
                            "downloads" to
                                mapOf(
                                    "artifact" to
                                        mapOf(
                                            "url" to libraryUrl,
                                            "path" to
                                                "net/fabricmc/fabric-loader/$loader/" +
                                                "fabric-loader-$loader.jar",
                                            "sha1" to sha1(library),
                                            "size" to library.size,
                                        ),
                                ),
                        ),
                    ),
                "arguments" to
                    mapOf(
                        "game" to emptyList<Any>(),
                        "jvm" to listOf("-DForeignKnot=true"),
                    ),
            ),
        )

    protected fun registerExternalRoot(
        store: LauncherStore,
        root: Path,
    ) {
        val directory = GameDirectory(name = "外部目录", path = root.toString())
        store.mutate {
            it.copy(gameDirectories = listOf(directory), activeGameDirectoryId = directory.id)
        }
    }

    protected fun versionDirectories(store: LauncherStore): List<String> =
        store.minecraftRoot
            .resolve("versions")
            .listDirectoryEntries()
            .map { it.name }
            .sorted()

    protected fun assertNoComponentBackups(store: LauncherStore) {
        val folder = store.directories.instanceBackups.resolve("components")
        assertTrue(!folder.exists() || folder.listDirectoryEntries().isEmpty())
    }
}
