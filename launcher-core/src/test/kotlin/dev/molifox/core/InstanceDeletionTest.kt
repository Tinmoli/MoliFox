// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例删除范围、确认信息和文件删除执行的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 删除实例：验证回收站默认行为、Shift 永久删除、特殊字符版本名、共用版本目录与受保护目录。 */
class InstanceDeletionTest {
    @TempDir lateinit var temporary: Path

    /** 记录 `moveToTrash` 的假平台：把目标移到临时“回收站”，便于断言到底交给了谁。 */
    private class TrashPlatform(
        private val bin: Path,
    ) : PlatformServices {
        var trashCalls = 0
        val trashed = mutableListOf<Path>()
        private var sequence = 0

        override val osName = "windows"
        override val architecture = "x86_64"
        override val javaExecutable = "java"
        override val classpathSeparator = ";"
        override val totalMemoryMb = 8192
        override val defaultDataDirectory: Path = bin.parent.resolve("data")

        override fun openFolder(directory: Path) = Unit

        override fun openUrl(url: String) = Unit

        override fun moveToTrash(file: Path) {
            trashCalls++
            trashed.add(file)
            bin.createDirectories()
            Files.move(file, bin.resolve("${sequence++}-${file.fileName}"))
        }
    }

    /** 造一个可被扫描的版本清单与客户端 jar；版本 id 原样用作目录名，不做任何模式匹配。 */
    private fun version(
        root: Path,
        id: String,
    ): Path {
        val folder = root.resolve("versions").resolve(id).apply { createDirectories() }
        writeJson(
            folder.resolve("$id.json"),
            mapOf(
                "id" to id,
                "mainClass" to "FixtureMinecraft",
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
                "libraries" to emptyList<String>(),
                "minecraftArguments" to
                    "--username ${'$'}{auth_player_name} --gameDir ${'$'}{game_directory}",
            ),
        )
        JarOutputStream(folder.resolve("$id.jar").outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("fixture.txt"))
            jar.write("fixture".toByteArray())
            jar.closeEntry()
        }
        return folder
    }

    @Test
    fun `missing game root preserves the technical cause without deleting files`() {
        val root = temporary.resolve("game")
        version(root, "Test")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance =
                controller.store.data.instances
                    .single { it.versionId == "Test" }
            val moved = temporary.resolve("moved-game")
            Files.move(root, moved)

            val failure =
                assertFailsWith<InstanceDeletionBlockedException> {
                    InstanceDeletionService(controller.store).plan(instance.id)
                }

            assertIs<NoSuchFileException>(failure.cause)
            assertTrue(failure.message.orEmpty().contains(root.toString()))
            assertTrue(moved.resolve("versions/Test/Test.json").isRegularFile())
            assertEquals(0, platform.trashCalls)
        }
    }

    @Test
    fun `deleting TwilightMC-1_21_11 with brackets moves exactly that version folder to trash`() {
        val root = temporary.resolve(".minecraft")
        val id = "TwilightMC-1.21.11[彗星版]_v1.3"
        val folder = version(root, id)
        folder
            .resolve("mods")
            .createDirectories()
            .resolve("a.jar")
            .writeText("mod")
        folder
            .resolve("config")
            .createDirectories()
            .resolve("options.toml")
            .writeText("cfg")
        folder
            .resolve("saves/world")
            .createDirectories()
            .resolve("level.dat")
            .writeText("save")
        folder
            .resolve("logs")
            .createDirectories()
            .resolve("latest.log")
            .writeText("log")
        val sibling = version(root, "KeepMe[1.21]_v2")
        root
            .resolve("saves/world")
            .createDirectories()
            .resolve("level.dat")
            .writeText("shared")
        root
            .resolve("libraries")
            .createDirectories()
            .resolve("x.jar")
            .writeText("lib")
        root
            .resolve("assets")
            .createDirectories()
            .resolve("a.bin")
            .writeText("asset")
        val expected = folder.toRealPath()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance =
                controller.store.data.instances
                    .single { it.versionId == id }
            val fallback =
                controller.store.data.instances
                    .single { it.id != instance.id }
            controller.store.mutate { it.copy(selectedInstanceId = instance.id) }
            assertEquals(listOf(expected), controller.deletionTargets(instance.id))
            // 确认框的细节：独立游戏目录 + saves 名单（名称与上次修改时间）。
            assertTrue(controller.hasSeparateGameDirectory(instance.id))
            assertEquals(listOf("world"), controller.deletionWorlds(instance.id).map { it.name })
            assertNotNull(controller.deletionWorlds(instance.id).single().modified)
            // 默认不按 Shift：只交给回收站一次，且目标正是用户示例的版本文件夹。
            val report = runBlocking { controller.deleteInstance(instance.id) }.getOrThrow()
            assertTrue(report.movedToTrash)
            assertEquals(1, platform.trashCalls)
            assertEquals(listOf(expected), platform.trashed)
            assertEquals(listOf(expected), report.removedPaths.map { Path.of(it) })
            assertTrue(report.freedBytes > 0)
            assertFalse(folder.exists())
            assertTrue(
                temporary
                    .resolve("trash")
                    .listDirectoryEntries()
                    .single { it.name.endsWith(id) }
                    .isDirectory(),
            )
            assertTrue(sibling.resolve("KeepMe[1.21]_v2.json").isRegularFile())
            assertTrue(root.resolve("versions").isDirectory())
            assertEquals("shared", root.resolve("saves/world/level.dat").readText())
            assertTrue(root.resolve("libraries/x.jar").isRegularFile())
            assertTrue(root.resolve("assets/a.bin").isRegularFile())
            assertTrue(
                controller.store.data.instances
                    .none { it.id == instance.id },
            )
            assertEquals(fallback.id, controller.store.data.selectedInstanceId)
        }
    }

    @Test
    fun `permanent deletion removes the isolated instance folders without touching trash`() {
        val root = temporary.resolve("game/.minecraft")
        root.createDirectories()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val created = controller.installer.createInstance(InstallRequest("隔离实例", "1.20.1"))
            val own = controller.store.instancePath(created.id)
            own
                .resolve("mods")
                .createDirectories()
                .resolve("m.jar")
                .writeText("mod")
            own
                .resolve("config")
                .createDirectories()
                .resolve("c.toml")
                .writeText("cfg")
            own
                .resolve("saves/world")
                .createDirectories()
                .resolve("level.dat")
                .writeText("save")
            own
                .resolve("logs")
                .createDirectories()
                .resolve("latest.log")
                .writeText("log")
            root
                .resolve("assets")
                .createDirectories()
                .resolve("a.bin")
                .writeText("asset")
            // 实例身份就是版本目录：删除目标只有它自己的 versions/隔离实例，不含别的版本目录。
            val expected = setOf(own.toRealPath())
            assertEquals(expected, controller.deletionTargets(created.id).toSet())
            val report =
                runBlocking {
                    controller.deleteInstance(created.id, permanent = true)
                }.getOrThrow()
            assertFalse(report.movedToTrash)
            assertEquals(0, platform.trashCalls)
            assertEquals(expected, report.removedPaths.map { Path.of(it) }.toSet())
            assertFalse(own.exists())
            assertTrue(root.resolve("assets/a.bin").isRegularFile())
            assertTrue(root.isDirectory())
        }
    }

    @Test
    fun `same game version string alone does not protect a version folder`() {
        val root = temporary.resolve("same-version/.minecraft")
        val versionFolder = version(root, "26.3")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val target =
                controller.store.data.instances
                    .single { it.versionId == "26.3" }
            // 另一个实例只是 gameVersion 字符串相同，versionId 和版本目录都不同，不是真实共用。
            controller.store.mutate {
                it.copy(
                    instances =
                        it.instances +
                            GameInstance(
                                name = "TwilightMC-26.3v1.0",
                                gameVersion = "26.3",
                                versionId = "TwilightMC-26.3v1.0",
                                gameDirectoryName = "TwilightMC-26.3v1.0",
                                gameDirectoryId = target.gameDirectoryId,
                            ),
                )
            }
            val expected = versionFolder.toRealPath()
            assertEquals(listOf(expected), controller.deletionTargets(target.id))
            assertTrue(controller.deletionPreserved(target.id).isEmpty())
            val report =
                runBlocking { controller.deleteInstance(target.id, permanent = true) }.getOrThrow()
            assertEquals(listOf(expected.toString()), report.removedPaths)
            assertTrue(report.preserved.isEmpty())
            assertTrue(report.freedBytes > 0)
            assertFalse(versionFolder.exists())
            val messages =
                controller.store.state.value.logs
                    .map { it.message }
            assertTrue(messages.none { "删除实例时保留" in it }, "同版本号不算共用，不应出现保留提示")
            assertTrue(
                messages.any { "已删除实例记录" in it && "释放 ${report.freedBytes} 字节" in it },
                "删除结果必须报告真实释放字节数：$messages",
            )
        }
    }

    @Test
    fun `a version folder referenced through inheritsFrom blocks deletion and keeps everything`() {
        val root = temporary.resolve("real-share/.minecraft")
        val versionFolder = version(root, "26.3")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val target =
                controller.store.data.instances
                    .single { it.versionId == "26.3" }
            // 另一个实例的版本 JSON 真的 inheritsFrom 26.3：这是真实共用，必须整次拒绝。
            writeJson(
                root.resolve("versions/TwilightMC-26.3v1.0/TwilightMC-26.3v1.0.json"),
                mapOf("id" to "TwilightMC-26.3v1.0", "inheritsFrom" to "26.3"),
            )
            controller.store.mutate {
                it.copy(
                    instances =
                        it.instances +
                            GameInstance(
                                name = "TwilightMC-26.3v1.0",
                                gameVersion = "26.3",
                                versionId = "TwilightMC-26.3v1.0",
                                gameDirectoryName = "TwilightMC-26.3v1.0",
                                gameDirectoryId = target.gameDirectoryId,
                            ),
                )
            }
            assertTrue(controller.deletionTargets(target.id).isEmpty())
            assertEquals(
                "无法删除：版本目录仍被实例「TwilightMC-26.3v1.0」使用；请先处理该实例",
                controller.deletionBlockReason(target.id),
            )
            val failure =
                runBlocking { controller.deleteInstance(target.id, permanent = true) }
                    .exceptionOrNull()
            assertIs<InstanceDeletionBlockedException>(failure)
            assertEquals(
                "无法删除：版本目录仍被实例「TwilightMC-26.3v1.0」使用；请先处理该实例",
                failure.message,
            )
            // 记录与文件都原样保留，磁盘一点没动。
            assertTrue(versionFolder.resolve("26.3.json").isRegularFile())
            assertTrue(
                controller.store.data.instances
                    .any { it.id == target.id },
            )
            assertEquals(0, platform.trashCalls)
            val messages =
                controller.store.state.value.logs
                    .map { it.message }
            assertTrue(
                messages.any {
                    it == "无法删除：版本目录仍被实例「TwilightMC-26.3v1.0」使用；请先处理该实例"
                },
                "日志必须显示同一条拒绝原因：$messages",
            )
            assertTrue(messages.none { "已删除实例记录" in it }, "被拒绝时不能出现已删除记录：$messages")
        }
    }

    @Test
    fun `a version folder shared by two instances blocks both deletions and keeps files intact`() {
        val root = temporary.resolve("shared/.minecraft")
        root.createDirectories()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val first = controller.installer.createInstance(InstallRequest("第一个", "1.20.1"))
            val second = controller.installer.createInstance(InstallRequest("第二个", "1.20.1"))
            val firstOwn = controller.store.versionGamePath(first).apply { createDirectories() }
            val secondOwn = controller.store.versionGamePath(second).apply { createDirectories() }
            // 新模型下每个实例有自己的版本目录；只有配置真正互相 inheritsFrom 才算共用。
            writeJson(
                firstOwn.resolve("第一个.json"),
                mapOf(
                    "id" to "第一个",
                    "inheritsFrom" to "第二个",
                    "mainClass" to "FixtureBase",
                    "gameVersion" to "1.20.1",
                ),
            )
            writeJson(
                secondOwn.resolve("第二个.json"),
                mapOf(
                    "id" to "第二个",
                    "inheritsFrom" to "第一个",
                    "mainClass" to "FixtureBase",
                    "gameVersion" to "1.20.1",
                ),
            )
            firstOwn
                .resolve("mods")
                .createDirectories()
                .resolve("a.jar")
                .writeText("mod")
            secondOwn
                .resolve("saves")
                .createDirectories()
                .resolve("level.dat")
                .writeText("save")
            val firstFailure = runBlocking { controller.deleteInstance(first.id) }.exceptionOrNull()
            assertIs<InstanceDeletionBlockedException>(firstFailure)
            assertEquals(
                "无法删除：版本目录仍被实例「第二个」使用；请先处理该实例",
                firstFailure.message,
            )
            // 整次拒绝：不删版本目录、不删自己的游戏目录、不移回收站、记录还在。
            assertEquals(0, platform.trashCalls)
            assertTrue(firstOwn.resolve("第一个.json").isRegularFile())
            assertEquals("mod", firstOwn.resolve("mods/a.jar").readText())
            assertEquals("save", secondOwn.resolve("saves/level.dat").readText())
            assertEquals(2, controller.store.data.instances.size)
            // 反过来也一样：占用者还在，第二名实例同样删不掉。
            val secondFailure =
                runBlocking {
                    controller.deleteInstance(
                        second.id,
                    )
                }.exceptionOrNull()
            assertIs<InstanceDeletionBlockedException>(secondFailure)
            assertEquals(
                "无法删除：版本目录仍被实例「第一个」使用；请先处理该实例",
                secondFailure.message,
            )
            assertTrue(
                controller.store.data.instances
                    .any { it.id == first.id },
            )
            assertTrue(
                controller.store.data.instances
                    .any { it.id == second.id },
            )
            val messages =
                controller.store.state.value.logs
                    .map { it.message }
            assertTrue(messages.none { "已删除实例记录" in it }, "没有任何一次删除成功：$messages")
        }
    }

    @Test
    fun `startup discovery drops a record whose version directory vanished`() {
        val root = temporary.resolve("discovery/.minecraft")
        root.createDirectories()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val created = controller.installer.createInstance(InstallRequest("会消失", "1.20.1"))
            val own = controller.store.instancePath(created.id)
            assertTrue(own.isDirectory())
            own.toFile().deleteRecursively()
            // 模拟下一次启动的发现流程：记录没有对应目录，必须从列表里消失并留一行日志。
            controller.directories.discover(listOf(StartupRoot(root.toRealPath(), 0)))
            assertTrue(
                controller.store.data.instances
                    .none { it.id == created.id },
            )
            assertTrue(
                controller.store.data.instances
                    .isEmpty(),
            )
            val messages =
                controller.store.state.value.logs
                    .map { it.message }
            assertTrue(
                messages.any { it == "清理失效实例记录：会消失（目录不存在）" },
                "启动发现必须记录清理：$messages",
            )
        }
    }
}
