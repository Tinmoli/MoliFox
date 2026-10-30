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

    /** 版本目录里的玩家内容：mods、config、saves 与日志。 */
    private fun createTrashedVersionContent(folder: Path) {
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
    }

    /** 与实例无关的共用游戏文件：saves、libraries 与 assets。 */
    private fun createSharedGameContent(root: Path) {
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
    }

    /** 删除前确认框要展示的细节：目标就是版本目录，另给出独立游戏目录与存档名单。 */
    private fun assertDeletionPlanForDialog(
        controller: LauncherController,
        instanceId: String,
        expected: Path,
    ) {
        assertEquals(listOf(expected), controller.deletionTargets(instanceId))
        assertTrue(controller.hasSeparateGameDirectory(instanceId))
        assertEquals(listOf("world"), controller.deletionWorlds(instanceId).map { it.name })
        assertNotNull(controller.deletionWorlds(instanceId).single().modified)
    }

    /** 默认删除只交给回收站一次，报告里的路径与释放空间也要对得上。 */
    private fun assertTrashOnlyReport(
        report: InstanceDeletionReport,
        platform: TrashPlatform,
        expected: Path,
    ) {
        assertTrue(report.movedToTrash)
        assertEquals(1, platform.trashCalls)
        assertEquals(listOf(expected), platform.trashed)
        assertEquals(listOf(expected), report.removedPaths.map { Path.of(it) })
        assertTrue(report.freedBytes > 0)
    }

    /** 版本目录与本地内容消失，回收站里就是它；同名其它版本与共享目录原样保留。 */
    private fun assertVersionFolderGoneAndSharedFilesKept(
        root: Path,
        folder: Path,
        sibling: Path,
        id: String,
    ) {
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
    }

    /** 独立实例目录里的玩家内容，以及共享游戏根里不该被删的 assets。 */
    private fun createIsolatedInstanceContent(
        own: Path,
        root: Path,
    ) {
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
    }

    /** 与 [target] 游戏版本字符串相同、但版本目录不同的另一个实例记录。 */
    private fun sameVersionInstance(target: GameInstance) =
        GameInstance(
            name = "TwilightMC-26.3v1.0",
            gameVersion = "26.3",
            versionId = "TwilightMC-26.3v1.0",
            gameDirectoryName = "TwilightMC-26.3v1.0",
            gameDirectoryId = target.gameDirectoryId,
        )

    /** 删除成功后日志必须报告真实释放字节数，且不能出现「保留」提示。 */
    private fun assertDeletionReported(
        controller: LauncherController,
        report: InstanceDeletionReport,
    ) {
        val messages =
            controller.store.state.value.logs
                .map { it.message }
        assertTrue(messages.none { "删除实例时保留" in it }, "同版本号不算共用，不应出现保留提示")
        assertTrue(
            messages.any { "已删除实例记录" in it && "释放 ${report.freedBytes} 字节" in it },
            "删除结果必须报告真实释放字节数：$messages",
        )
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
        createTrashedVersionContent(folder)
        val sibling = version(root, "KeepMe[1.21]_v2")
        createSharedGameContent(root)
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
            assertDeletionPlanForDialog(controller, instance.id, expected)
            // 默认不按 Shift：只交给回收站一次，且目标正是用户示例的版本文件夹。
            val report = runBlocking { controller.deleteInstance(instance.id) }.getOrThrow()
            assertTrashOnlyReport(report, platform, expected)
            assertVersionFolderGoneAndSharedFilesKept(root, folder, sibling, id)
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
            createIsolatedInstanceContent(own, root)
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
                it.copy(instances = it.instances + sameVersionInstance(target))
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
            assertDeletionReported(controller, report)
        }
    }
}
