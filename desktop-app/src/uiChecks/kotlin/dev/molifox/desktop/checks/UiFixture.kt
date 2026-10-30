// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 通过当前目录扫描和配置接口建立合法实例，测试不依赖旧配置迁移。

package dev.molifox.desktop.checks

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.molifox.core.GameDirectoryService
import dev.molifox.core.GameInstance
import dev.molifox.core.LauncherController
import dev.molifox.core.LauncherStore
import dev.molifox.core.NetworkClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

private const val NBT_COMPOUND = 10
private const val NBT_STRING = 8
private const val NBT_END = 0

internal class UiFixture(
    val root: Path,
) : AutoCloseable {
    val platform = FixturePlatform(root)
    val picker = FixtureFilePicker()
    val network = FixtureNetwork()
    val gameRoot: Path = root.resolve("game")
    val source: Path = gameRoot.resolve("versions/UI-Main")
    val destination: Path = root.resolve("destination-game")

    /** 第二个实例：用于确认切换实例后不会显示另一个实例的光影头像。 */
    val other: Path = gameRoot.resolve("versions/UI-Other")

    /** 假网络提供的头像地址；只有登记了该地址的光影包才会请求它。 */
    val networkIconUrl = "https://cdn.modrinth.com/data/fixture-shader/icon.png"
    val auroraIcon: ByteArray = pngIcon(0xFF, 0x00, 0xFF)
    val otherIcon: ByteArray = pngIcon(0x00, 0xE0, 0xFF)
    val networkIcon: ByteArray = pngIcon(0x42, 0xC8, 0x6A)

    val controller: LauncherController

    init {
        seedFiles()
        val store = LauncherStore(platform, platform.defaultDataDirectory)
        store.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        gameRoot = gameRoot.toString(),
                        autoCheckUpdates = false,
                        themeMode = "light",
                        animationsEnabled = false,
                        isolateVersions = true,
                        javaPath = platform.javaPath,
                        memoryMb = 2048,
                    ),
            )
        }
        GameDirectoryService(store).add(gameRoot)
        controller =
            LauncherController(
                platform,
                platform.defaultDataDirectory,
                networkClient = NetworkClient(interceptors = listOf(network)),
            )
        val instance =
            controller.store.data.instances
                .single { it.name == "UI-Main" }
        check(instance.installed && !instance.broken && !instance.externalProfile)
        controller.selectInstance(instance.id)
    }

    /** 当前选中的实例；改名后 id 会变化，场景不能用构造时的快照。 */
    fun selectedInstance(): GameInstance =
        controller.store.instance(checkNotNull(controller.store.data.selectedInstanceId))

    fun instanceNamed(name: String): GameInstance =
        controller.store.data.instances.single {
            it.name ==
                name
        }

    fun instanceDirectory(instance: GameInstance): Path = controller.store.versionGamePath(instance)

    /** 已落盘的实例设置，用于确认自动保存真的写进 launcher.json 而不是只改了内存状态。 */
    fun persistedInstanceSettings(folder: String): JsonObject? =
        runCatching {
            val config = controller.store.directories.configuration
            if (!Files.isRegularFile(config)) return@runCatching null
            JsonParser
                .parseString(Files.readString(config))
                .asJsonObject
                .getAsJsonObject("instanceSettings")
                ?.getAsJsonObject(folder)
        }.getOrNull()

    private fun seedFiles() {
        listOf("mods", "resourcepacks", "shaderpacks", "schematics", "logs", "config")
            .forEach { Files.createDirectories(source.resolve(it)) }
        Files.writeString(source.resolve("schematics/example.litematic"), "fixture")
        Files.writeString(source.resolve("config/preserve.txt"), "keep me")
        writeFixtureJson(
            source.resolve("UI-Main.json"),
            mapOf(
                "id" to "UI-Main",
                "gameVersion" to "1.20.1",
                "molifoxManaged" to true,
                "type" to "release",
                "mainClass" to "fixture.NotLaunched",
                "libraries" to emptyList<Any>(),
                "minecraftArguments" to "--username ${'$'}{auth_player_name}",
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
            ),
        )
        JarOutputStream(Files.newOutputStream(source.resolve("UI-Main.jar"))).use {
            it.putNextEntry(JarEntry("fixture.txt"))
            it.write("fixture".toByteArray())
            it.closeEntry()
        }
        seedWorld()
        seedShaderPacks()
        seedOtherInstance()
        Files.createDirectories(destination)
    }

    /**
     * 三个光影包分别覆盖头像的三种来源：包内 PNG、已登记的 iconUrl、两者都没有。
     *
     * 第四个包属于另一个实例，用来确认切换实例时不会串图。
     */
    private fun seedShaderPacks() {
        val cloud = source.resolve("shaderpacks/Cloud.zip")
        writeZip(
            source.resolve("shaderpacks/Aurora.zip"),
            mapOf(
                "icon.png" to auroraIcon,
                "shaders/aurora.glsl" to "fixture".toByteArray(),
            ),
        )
        writeZip(
            cloud,
            mapOf("shaders/cloud.glsl" to "fixture".toByteArray()),
        )
        writeZip(
            source.resolve("shaderpacks/Plain.zip"),
            mapOf("shaders/plain.glsl" to "fixture".toByteArray()),
        )
        writeFixtureJson(
            source.resolve(".molifox-content.json"),
            mapOf(
                "fixture-cloud" to
                    mapOf(
                        "file" to "Cloud.zip",
                        "folder" to "shaderpacks",
                        "versionId" to "fixture-1",
                        "hash" to sha512(cloud),
                        "hashAlgorithm" to "SHA-512",
                        "iconUrl" to networkIconUrl,
                    ),
            ),
        )
        network.serve(networkIconUrl, networkIcon)
    }

    private fun seedOtherInstance() {
        listOf("mods", "shaderpacks", "config")
            .forEach { Files.createDirectories(other.resolve(it)) }
        writeFixtureJson(
            other.resolve("UI-Other.json"),
            mapOf(
                "id" to "UI-Other",
                "gameVersion" to "1.20.1",
                "molifoxManaged" to true,
                "type" to "release",
                "mainClass" to "fixture.NotLaunched",
                "libraries" to emptyList<Any>(),
                "minecraftArguments" to "--username ${'$'}{auth_player_name}",
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
            ),
        )
        JarOutputStream(Files.newOutputStream(other.resolve("UI-Other.jar"))).use {
            it.putNextEntry(JarEntry("fixture.txt"))
            it.write("fixture".toByteArray())
            it.closeEntry()
        }
        writeZip(
            other.resolve("shaderpacks/Other.zip"),
            mapOf(
                "icon.png" to otherIcon,
                "shaders/other.glsl" to "fixture".toByteArray(),
            ),
        )
    }

    private fun seedWorld() {
        val world = Files.createDirectories(source.resolve("saves/UI World"))
        DataOutputStream(GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))).use {
            it.writeByte(NBT_COMPOUND)
            it.writeUTF("")
            it.writeByte(NBT_COMPOUND)
            it.writeUTF("Data")
            it.writeByte(NBT_STRING)
            it.writeUTF("LevelName")
            it.writeUTF("UI World")
            it.writeByte(NBT_END)
            it.writeByte(NBT_END)
        }
    }

    override fun close() {
        controller.close()
    }

    private companion object {
        fun pngIcon(
            red: Int,
            green: Int,
            blue: Int,
        ): ByteArray {
            val size = 16
            val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
            val color = (red shl 16) or (green shl 8) or blue
            for (y in 0 until size) {
                for (x in 0 until size) {
                    image.setRGB(x, y, color)
                }
            }
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }

        fun writeZip(
            path: Path,
            entries: Map<String, ByteArray>,
        ) {
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }

        fun sha512(path: Path): String =
            HexFormat
                .of()
                .formatHex(
                    MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(path)),
                )
    }
}
