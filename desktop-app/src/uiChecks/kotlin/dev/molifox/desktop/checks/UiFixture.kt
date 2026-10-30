// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 通过当前目录扫描和配置接口建立合法实例，测试不依赖旧配置迁移。

package dev.molifox.desktop.checks

import dev.molifox.core.GameDirectoryService
import dev.molifox.core.LauncherController
import dev.molifox.core.LauncherStore
import dev.molifox.core.NetworkClient
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.GZIPOutputStream

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
                        javaPath = platform.javaExecutable,
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
        Files.createDirectories(destination)
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
}
