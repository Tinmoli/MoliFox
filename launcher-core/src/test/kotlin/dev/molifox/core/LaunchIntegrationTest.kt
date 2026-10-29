// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实际 Java 子进程与启动配置的集成的行为与边界。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LaunchIntegrationTest {
    @TempDir lateinit var temporary: Path

    private fun jar(name: String, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            JarOutputStream(output).use { zip ->
                zip.putNextEntry(JarEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            output.toByteArray()
        }

    @Test
    fun `install repair and launch a complete fixture through a real Java process`(): Unit =
        runBlocking {
            val directory =
                temporary.resolve("中文 launcher with spaces").apply { createDirectories() }
            val source = temporary.resolve("FixtureMinecraft.java")
            source.writeText(
                """public class FixtureMinecraft { public static void main(String[] args) throws Exception { System.out.println("MOLIFOX_GAME_STARTED"); System.out.println("PLAYER_ARGS=" + String.join("|", args)); System.out.println("LOG_CONFIG=" + System.getProperty("fixture.config")); System.out.println("REAL_CLASSPATH=" + System.getProperty("java.class.path")); System.out.println("JVM_FLAGS=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()); System.out.println("LAUNCH_OPTION=" + System.getProperty("fixture.option")); System.out.println("GAME_TMP=" + System.getenv("TMP")); System.out.println("NATIVE_FIXTURE=" + java.nio.file.Files.readString(java.nio.file.Path.of(System.getProperty("java.library.path"), "fixture-native.bin"))); System.out.println("GAME_CWD=" + System.getProperty("user.dir")); } }"""
            )
            val compiler = ToolProvider.getSystemJavaCompiler()
            assertNotNull(compiler, "This integration test needs a JDK")
            assertEquals(
                0,
                compiler.run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-d",
                    temporary.toString(),
                    source.toString(),
                ),
            )
            val clientJar =
                jar(
                    "FixtureMinecraft.class",
                    temporary.resolve("FixtureMinecraft.class").readBytes(),
                )
            val nativeJar = jar("fixture-native.bin", "native fixture".toByteArray())
            val libraryJar = jar("fixture.txt", "library fixture".toByteArray())
            val asset = "sound fixture".toByteArray()
            val assetHash = sha1(asset)
            val config = "<configuration/>".toByteArray()

            MockWebServer().use { server ->
                val baseUrl = server.loopbackUrl("/").toString().removeSuffix("/")
                val profile =
                    gson
                        .toJson(
                            mapOf(
                                "id" to "fixture",
                                "type" to "release",
                                "mainClass" to "FixtureMinecraft",
                                "javaVersion" to
                                    mapOf("majorVersion" to Runtime.version().feature()),
                                "downloads" to
                                    mapOf(
                                        "client" to
                                            mapOf(
                                                "url" to "$baseUrl/client.jar",
                                                "sha1" to sha1(clientJar),
                                                "size" to clientJar.size,
                                            )
                                    ),
                                "assetIndex" to
                                    mapOf("id" to "fixture", "url" to "$baseUrl/assets.json"),
                                "logging" to
                                    mapOf(
                                        "client" to
                                            mapOf(
                                                "argument" to "-Dfixture.config=${'$'}{path}",
                                                "file" to
                                                    mapOf(
                                                        "id" to "fixture.xml",
                                                        "url" to "$baseUrl/config.xml",
                                                        "sha1" to sha1(config),
                                                    ),
                                            )
                                    ),
                                "libraries" to
                                    listOf(
                                        mapOf(
                                            "name" to "dev.molifox:fixture:1",
                                            "downloads" to
                                                mapOf(
                                                    "artifact" to
                                                        mapOf(
                                                            "path" to
                                                                "dev/molifox/fixture/1/fixture-1.jar",
                                                            "url" to "$baseUrl/library.jar",
                                                            "sha1" to sha1(libraryJar),
                                                        )
                                                ),
                                        ),
                                        mapOf(
                                            "name" to "dev.molifox:natives:1",
                                            "natives" to
                                                mapOf(
                                                    "windows" to "natives-windows",
                                                    "linux" to "natives-linux",
                                                    "osx" to "natives-osx",
                                                ),
                                            "downloads" to
                                                mapOf(
                                                    "classifiers" to
                                                        listOf("windows", "linux", "osx")
                                                            .associate {
                                                                "natives-$it" to
                                                                    mapOf(
                                                                        "path" to
                                                                            "dev/molifox/natives/1/natives-$it.jar",
                                                                        "url" to
                                                                            "$baseUrl/native.jar",
                                                                        "sha1" to sha1(nativeJar),
                                                                    )
                                                            }
                                                ),
                                        ),
                                    ),
                                "arguments" to
                                    mapOf(
                                        "jvm" to
                                            listOf(
                                                "-Djava.library.path=${'$'}{natives_directory}",
                                                "-cp",
                                                "${'$'}{classpath}",
                                            ),
                                        "game" to
                                            listOf(
                                                "--username",
                                                "${'$'}{auth_player_name}",
                                                "--accessToken",
                                                "${'$'}{auth_access_token}",
                                                mapOf(
                                                    "rules" to
                                                        listOf(
                                                            mapOf(
                                                                "action" to "allow",
                                                                "features" to
                                                                    mapOf(
                                                                        "has_custom_resolution" to
                                                                            true
                                                                    ),
                                                            )
                                                        ),
                                                    "value" to
                                                        listOf(
                                                            "--width",
                                                            "${'$'}{resolution_width}",
                                                            "--height",
                                                            "${'$'}{resolution_height}",
                                                        ),
                                                ),
                                            ),
                                    ),
                            )
                        )
                        .toByteArray()
                val manifest =
                    gson
                        .toJson(
                            mapOf(
                                "versions" to
                                    listOf(
                                        mapOf(
                                            "id" to "fixture",
                                            "type" to "release",
                                            "url" to "$baseUrl/profile.json",
                                            "releaseTime" to "2026-10-26T00:00:00Z",
                                            "sha1" to sha1(profile),
                                        )
                                    )
                            )
                        )
                        .toByteArray()
                val assets =
                    gson
                        .toJson(
                            mapOf(
                                "virtual" to true,
                                "objects" to
                                    mapOf(
                                        "minecraft/sounds/fixture.ogg" to
                                            mapOf("hash" to assetHash, "size" to asset.size)
                                    ),
                            )
                        )
                        .toByteArray()
                val routes =
                    mapOf(
                        "/manifest.json" to manifest,
                        "/profile.json" to profile,
                        "/assets.json" to assets,
                        "/client.jar" to clientJar,
                        "/library.jar" to libraryJar,
                        "/native.jar" to nativeJar,
                        "/config.xml" to config,
                        "/objects/${assetHash.take(2)}/$assetHash" to asset,
                    )
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            routes[request.path]?.let { MockResponse().setBody(Buffer().write(it)) }
                                ?: MockResponse().setResponseCode(404)
                    }
                val platform = DesktopPlatform()
                val store = LauncherStore(platform, directory)
                val javaPath =
                    Path.of(System.getProperty("java.home"), "bin", platform.javaExecutable)
                        .toString()
                store.mutate {
                    it.copy(settings = it.settings.copy(javaPath = javaPath, memoryMb = 768))
                }
                val network = NetworkClient()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                try {
                    val java = JavaService(store, network)
                    assertNotNull(
                        java.inspect(javaPath),
                        "Java inspection must drain process output without blocking",
                    )
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            java,
                            "$baseUrl/manifest.json",
                            "$baseUrl/objects",
                        )
                    val accounts = AccountService(store, network, scope)
                    accounts.addOffline("MoliFox")
                    val instance = installer.createInstance(InstallRequest("我的测试世界", "fixture"))
                    val jobs = JobQueue(store, scope)
                    jobs.enqueue("install fixture") { installer.install(instance.id, this) }
                    withTimeout(45000) { jobs.awaitIdle() }
                    assertEquals(
                        JobStatus.Completed,
                        store.state.value.jobs.first().status,
                        store.state.value.jobs.first().error,
                    )
                    assertTrue(store.instance(instance.id).installed)
                    // 安装只发布实例自己的目录：配置与客户端 JAR 都在 versions/我的测试世界/ 里。
                    assertEquals("我的测试世界", store.instance(instance.id).versionId)
                    val gameJar = store.instancePath(instance.id).resolve("我的测试世界.jar")
                    assertEquals(sha1(clientJar), digest(gameJar))
                    assertFalse(store.minecraftRoot.resolve("versions/fixture").exists())
                    gameJar.writeText("corrupted cache")
                    jobs.enqueue("repair fixture") { installer.install(instance.id, this) }
                    withTimeout(45000) { jobs.awaitIdle() }
                    assertEquals(sha1(clientJar), digest(gameJar))
                    val launcher = GameLauncher(store, installer, java, accounts, scope)
                    // Launch itself must repair damaged and missing files, without a manual repair.
                    gameJar.writeText("damaged after installation")
                    store.minecraftRoot
                        .resolve("libraries/dev/molifox/fixture/1/fixture-1.jar")
                        .deleteExisting()
                    store.minecraftRoot
                        .resolve("assets/objects/${assetHash.take(2)}/$assetHash")
                        .deleteExisting()
                    if (store.platform.osName == "windows")
                        store.mutate {
                            it.copy(
                                settings =
                                    it.settings.copy(
                                        extraJvmArgs = "-Dfixture.padding=" + "x".repeat(25_000)
                                    )
                            )
                        }
                    jobs.enqueue("launch fixture") { launcher.launch(instance.id, this) }
                    withTimeout(45000) {
                        jobs.awaitIdle()
                        while (
                            store.state.value.logs.none { it.message == "MOLIFOX_GAME_STARTED" } ||
                                store.state.value.runningInstanceId != null
                        ) {
                            if (
                                store.state.value.runningInstanceId == null &&
                                    store.state.value.jobs.none {
                                        it.status in listOf(JobStatus.Queued, JobStatus.Running)
                                    }
                            ) {
                                error(
                                    "Fixture launch failed: " +
                                        store.state.value.logs.joinToString("\n") { it.message }
                                )
                            }
                            delay(50)
                        }
                    }
                    assertTrue(
                        store.state.value.logs.any {
                            it.message.contains(
                                "PLAYER_ARGS=--username|MoliFox|--accessToken|0|--width|1280|--height|720"
                            )
                        }
                    )
                    assertTrue(
                        store.state.value.logs.any {
                            it.message.contains("LOG_CONFIG=") && it.message.contains("fixture.xml")
                        }
                    )
                    assertTrue(
                        store.state.value.logs.any { it.message == "NATIVE_FIXTURE=native fixture" }
                    )
                    assertFalse(
                        store
                            .instancePath(instance.id)
                            .resolve("natives/fixture-native.bin")
                            .exists()
                    )
                    assertEquals(
                        "sound fixture",
                        store.minecraftRoot
                            .resolve("assets/virtual/fixture/minecraft/sounds/fixture.ogg")
                            .readText(),
                    )
                    assertNotNull(store.instance(instance.id).lastPlayed)
                    assertTrue(
                        store.state.value.logs.any {
                            it.message == "GAME_TMP=${store.temporaryDirectory}"
                        }
                    )
                    assertTrue(
                        store.state.value.logs.any {
                            it.message == "GAME_CWD=${store.instancePath(instance.id)}"
                        }
                    )
                    assertEquals(sha1(clientJar), digest(gameJar))
                    assertEquals(
                        assetHash,
                        digest(
                            store.minecraftRoot.resolve(
                                "assets/objects/${assetHash.take(2)}/$assetHash"
                            )
                        ),
                    )
                    val runtimeClasspath =
                        store.state.value.logs
                            .first { it.message.startsWith("REAL_CLASSPATH=") }
                            .message
                    assertTrue(
                        runtimeClasspath.contains(gameJar.toAbsolutePath().toString()),
                        "Fabric must be able to see the actual Minecraft jar",
                    )
                    assertTrue(
                        runtimeClasspath.contains("fixture-1.jar"),
                        "Loader dependencies must be listed individually",
                    )
                    assertFalse(runtimeClasspath.contains(".molifox-classpath.jar"))
                    store.mutate {
                        it.copy(
                            settings =
                                it.settings.copy(
                                    width = 1024,
                                    height = 768,
                                    initialMemoryMb = 256,
                                    fullscreen = false,
                                    extraJvmArgs = "-Dfixture.option=global",
                                )
                        )
                    }
                    store.updateInstance(instance.id) {
                        it.copy(
                            memoryMb = 1536,
                            initialMemoryMb = 768,
                            width = 1600,
                            height = 900,
                            windowMode = "fullscreen",
                            extraJvmArgs = "-Dfixture.option=instance",
                        )
                    }
                    val requestsBeforeOfflineLaunch = server.requestCount
                    jobs.enqueue("launch with instance overrides") {
                        launcher.launch(instance.id, this)
                    }
                    withTimeout(45000) {
                        jobs.awaitIdle()
                        while (
                            store.state.value.logs.none {
                                it.message == "LAUNCH_OPTION=instance"
                            } || store.state.value.runningInstanceId != null
                        ) delay(30)
                    }
                    assertTrue(
                        store.state.value.logs.any {
                            it.message.contains(
                                "PLAYER_ARGS=--username|MoliFox|--accessToken|0|--width|1600|--height|900|--fullscreen"
                            )
                        }
                    )
                    assertEquals(
                        requestsBeforeOfflineLaunch,
                        server.requestCount,
                        "Complete local game must not request downloads during launch",
                    )
                    assertTrue(
                        store.state.value.logs.any {
                            it.message.startsWith("JVM_FLAGS=") &&
                                it.message.contains("-Xms768M") &&
                                it.message.contains("-Xmx1536M")
                        }
                    )
                    val configured = store.instance(instance.id)
                    val runtime = java.inspect(javaPath) ?: error("Runtime unavailable")
                    val inherited =
                        LaunchPlanner(store)
                            .plan(
                                configured.copy(
                                    memoryMb = 0,
                                    initialMemoryMb = 0,
                                    width = 0,
                                    height = 0,
                                    windowMode = "windowed",
                                    extraJvmArgs = "",
                                ),
                                VersionResolver(store.minecraftRoot, store.platform)
                                    .resolve(configured.versionId),
                                runtime,
                                GameCredentials("MoliFox", offlineUuid("MoliFox")),
                            )
                    assertContains(inherited.arguments, "-Xms256M")
                    assertContains(inherited.arguments, "-Dfixture.option=global")
                    assertEquals(
                        "1024",
                        inherited.arguments[inherited.arguments.indexOf("--width") + 1],
                    )
                    assertFalse("--fullscreen" in inherited.arguments)
                } finally {
                    scope.cancel()
                    network.close()
                }
            }
        }

    @Test
    fun `pack traversal is rejected before files can escape instance storage`() = runBlocking {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("data"))
        val network = NetworkClient()
        try {
            val installer = MinecraftInstaller(store, network, JavaService(store, network))
            val pack = temporary.resolve("bad.mrpack")
            val json =
                mapOf(
                    "formatVersion" to 1,
                    "game" to "minecraft",
                    "name" to "Bad",
                    "dependencies" to mapOf("minecraft" to "1.20.1"),
                    "files" to
                        listOf(
                            mapOf(
                                "path" to "../../escape.jar",
                                "hashes" to mapOf("sha1" to "a".repeat(40)),
                                "downloads" to listOf("https://cdn.modrinth.com/test.jar"),
                            )
                        ),
                )
            pack.writeBytes(jar("modrinth.index.json", gson.toJson(json).toByteArray()))
            assertFails {
                ContentService(store, network, installer)
                    .importPack(pack, JobContext(store, "test"))
            }
            assertTrue(store.data.instances.isEmpty())
            assertFalse(temporary.resolve("escape.jar").exists())
        } finally {
            network.close()
        }
    }
}
