// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责编译启动集成测试用的 Java 客户端并提供可控的下载服务端。

package dev.molifox.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val FIXTURE_SOURCE =
    """public class FixtureMinecraft {
        public static void main(String[] args) throws Exception {
            System.out.println("MOLIFOX_GAME_STARTED");
            System.out.println("PLAYER_ARGS=" + String.join("|", args));
            System.out.println("LOG_CONFIG=" + System.getProperty("fixture.config"));
            System.out.println(
                "REAL_CLASSPATH=" + System.getProperty("java.class.path"));
            System.out.println(
                "JVM_FLAGS=" +
                    java.lang.management.ManagementFactory
                        .getRuntimeMXBean()
                        .getInputArguments());
            System.out.println(
                "LAUNCH_OPTION=" + System.getProperty("fixture.option"));
            System.out.println("GAME_TMP=" + System.getenv("TMP"));
            System.out.println(
                "NATIVE_FIXTURE=" +
                    java.nio.file.Files.readString(
                        java.nio.file.Path.of(
                            System.getProperty("java.library.path"),
                            "fixture-native.bin")));
            System.out.println("GAME_CWD=" + System.getProperty("user.dir"));
        } }"""

/**
 * 完整的启动夹具：编译一个只打印参数的 Java 客户端，并把 profile、manifest、
 * 资源索引与各个下载文件挂到 MockWebServer 上。
 */
internal class LaunchFixtureServer(
    private val temporary: Path,
) {
    private val nativeJar = jar("fixture-native.bin", "native fixture".toByteArray())
    private val libraryJar = jar("fixture.txt", "library fixture".toByteArray())
    private val asset = "sound fixture".toByteArray()
    private val config = "<configuration/>".toByteArray()

    /** 编译出来的客户端 JAR，安装、修复与启动后都要核对它的摘要。 */
    val clientJar: ByteArray = compileClient()

    /** 资源对象的哈希，同时是 objects/ 下的路径片段。 */
    val assetHash: String = sha1(asset)

    /** MockWebServer 的根地址，末尾不带斜杠。 */
    var baseUrl: String = ""
        private set

    /** 挂上固定响应；[baseUrl] 也随之确定。 */
    fun serve(server: MockWebServer) {
        baseUrl = server.loopbackUrl("/").toString().removeSuffix("/")
        val profile = profileJson()
        val manifest = manifestJson(profile)
        val assets = assetsJson()
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
    }

    /** 编译打印启动参数的 Java 客户端，并把它打成 JAR。 */
    private fun compileClient(): ByteArray {
        val source = temporary.resolve("FixtureMinecraft.java")
        source.writeText(FIXTURE_SOURCE)
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
        return clientJar
    }

    /** Mojang 风格 profile：客户端 JAR、资源索引与库/参数都在这里。 */
    private fun profileJson(): ByteArray =
        gson
            .toJson(
                mapOf(
                    "id" to "fixture",
                    "type" to "release",
                    "mainClass" to "FixtureMinecraft",
                    "javaVersion" to
                        mapOf("majorVersion" to Runtime.version().feature()),
                    "downloads" to clientDownload(),
                    "assetIndex" to
                        mapOf("id" to "fixture", "url" to "$baseUrl/assets.json"),
                    "logging" to loggingConfig(),
                    "libraries" to libraries(),
                    "arguments" to arguments(),
                ),
            ).toByteArray()

    /** 客户端 JAR 的下载信息：地址、摘要与大小。 */
    private fun clientDownload(): Map<String, Any> =
        mapOf(
            "client" to
                mapOf(
                    "url" to "$baseUrl/client.jar",
                    "sha1" to sha1(clientJar),
                    "size" to clientJar.size,
                ),
        )

    /** 日志配置文件的下载信息与替换参数。 */
    private fun loggingConfig(): Map<String, Any> =
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
                ),
        )

    /** 库列表：一个普通库与一个按平台取 classifier 的本地库。 */
    private fun libraries(): List<Any> = listOf(libraryArtifact(), nativesLibrary())

    /** 普通库：直接下载单个 JAR。 */
    private fun libraryArtifact(): Map<String, Any> =
        mapOf(
            "name" to "dev.molifox:fixture:1",
            "downloads" to
                mapOf(
                    "artifact" to
                        mapOf(
                            "path" to
                                "dev/molifox/fixture/1/" +
                                "fixture-1.jar",
                            "url" to "$baseUrl/library.jar",
                            "sha1" to sha1(libraryJar),
                        ),
                ),
        )

    /** 本地库：三个平台的 classifier 指向同一个 JAR。 */
    private fun nativesLibrary(): Map<String, Any> =
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
                                            "dev/molifox/" +
                                            "natives/1/" +
                                            "natives-$it.jar",
                                        "url" to
                                            "$baseUrl/native.jar",
                                        "sha1" to sha1(nativeJar),
                                    )
                            },
                ),
        )

    /** 启动参数：JVM 参数与游戏参数，窗口参数带 has_custom_resolution 规则。 */
    private fun arguments(): Map<String, Any> =
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
                                                true,
                                        ),
                                ),
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
        )

    /** 只有一条版本的 manifest，摘要指向 [profile]。 */
    private fun manifestJson(profile: ByteArray): ByteArray =
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
                            ),
                        ),
                ),
            ).toByteArray()

    /** 一个虚拟资源索引，资源内容由 [serve] 的 /objects 路由提供。 */
    private fun assetsJson(): ByteArray =
        gson
            .toJson(
                mapOf(
                    "virtual" to true,
                    "objects" to
                        mapOf(
                            "minecraft/sounds/fixture.ogg" to
                                mapOf("hash" to assetHash, "size" to asset.size),
                        ),
                ),
            ).toByteArray()
}

internal fun jar(
    name: String,
    bytes: ByteArray,
): ByteArray =
    ByteArrayOutputStream().use { output ->
        JarOutputStream(output).use { zip ->
            zip.putNextEntry(JarEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        output.toByteArray()
    }
