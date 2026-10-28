package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 删除实例：验证回收站默认行为、Shift 永久删除、特殊字符版本名、共用版本目录与受保护目录。 */
class InstanceDeletionTest {
    @TempDir lateinit var temporary: Path

    /** 记录 `moveToTrash` 的假平台：把目标移到临时“回收站”，便于断言到底交给了谁。 */
    private class TrashPlatform(private val bin: Path) : PlatformServices {
        var trashCalls = 0
        val trashed = mutableListOf<Path>()
        private var sequence = 0

        override val osName = "windows"
        override val architecture = "x86_64"
        override val javaExecutable = "java"
        override val classpathSeparator = ";"
        override val totalMemoryMb = 8192
        override val defaultDataDirectory: Path = bin.parent.resolve("data")

        override fun openFolder(directory: Path) {}

        override fun openUrl(url: String) {}

        override fun moveToTrash(file: Path) {
            trashCalls++
            trashed.add(file)
            bin.createDirectories()
            Files.move(file, bin.resolve("${sequence++}-${file.fileName}"))
        }
    }

    /** 造一个可被扫描的版本清单与客户端 jar；版本 id 原样用作目录名，不做任何模式匹配。 */
    private fun version(root: Path, id: String): Path {
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
    fun `deleting TwilightMC-1_21_11 with brackets moves exactly that version folder to trash`() {
        val root = temporary.resolve(".minecraft")
        val id = "TwilightMC-1.21.11[彗星版]_v1.3"
        val folder = version(root, id)
        folder.resolve("mods").createDirectories().resolve("a.jar").writeText("mod")
        folder.resolve("config").createDirectories().resolve("options.toml").writeText("cfg")
        folder.resolve("saves/world").createDirectories().resolve("level.dat").writeText("save")
        folder.resolve("logs").createDirectories().resolve("latest.log").writeText("log")
        val sibling = version(root, "KeepMe[1.21]_v2")
        root.resolve("saves/world").createDirectories().resolve("level.dat").writeText("shared")
        root.resolve("libraries").createDirectories().resolve("x.jar").writeText("lib")
        root.resolve("assets").createDirectories().resolve("a.bin").writeText("asset")
        val expected = folder.toRealPath()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance = controller.store.data.instances.single { it.versionId == id }
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
            assertTrue(temporary.resolve("trash").listDirectoryEntries().single { it.name.endsWith(id) }.isDirectory())
            assertTrue(sibling.resolve("KeepMe[1.21]_v2.json").isRegularFile())
            assertTrue(root.resolve("versions").isDirectory())
            assertEquals("shared", root.resolve("saves/world/level.dat").readText())
            assertTrue(root.resolve("libraries/x.jar").isRegularFile())
            assertTrue(root.resolve("assets/a.bin").isRegularFile())
            assertTrue(controller.store.data.instances.none { it.id == instance.id })
            assertNull(controller.store.data.selectedInstanceId)
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
            val own = root.resolve("instances").resolve(created.id)
            own.resolve("mods").createDirectories().resolve("m.jar").writeText("mod")
            own.resolve("config").createDirectories().resolve("c.toml").writeText("cfg")
            own.resolve("saves/world").createDirectories().resolve("level.dat").writeText("save")
            own.resolve("logs").createDirectories().resolve("latest.log").writeText("log")
            val versionFolder = version(root, "1.20.1")
            root.resolve("assets").createDirectories().resolve("a.bin").writeText("asset")
            val expected = setOf(own.toRealPath(), versionFolder.toRealPath())
            assertEquals(expected, controller.deletionTargets(created.id).toSet())
            val report = runBlocking { controller.deleteInstance(created.id, permanent = true) }.getOrThrow()
            assertFalse(report.movedToTrash)
            assertEquals(0, platform.trashCalls)
            assertEquals(expected, report.removedPaths.map { Path.of(it) }.toSet())
            assertFalse(own.exists())
            assertFalse(versionFolder.exists())
            assertTrue(root.resolve("assets/a.bin").isRegularFile())
            assertTrue(root.isDirectory())
        }
    }

    @Test
    fun `a version folder shared by another instance survives until the last instance is deleted`() {
        val root = temporary.resolve("shared/.minecraft")
        root.createDirectories()
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val first = controller.installer.createInstance(InstallRequest("第一个", "1.20.1"))
            val second = controller.installer.createInstance(InstallRequest("第二个", "1.20.1"))
            val versionFolder = version(root, "1.20.1")
            val firstOwn = root.resolve("instances").resolve(first.id).apply { createDirectories() }
            val secondOwn = root.resolve("instances").resolve(second.id).apply { createDirectories() }
            firstOwn.resolve("mods").createDirectories().resolve("a.jar").writeText("mod")
            secondOwn.resolve("saves").createDirectories().resolve("level.dat").writeText("save")
            runBlocking { controller.deleteInstance(first.id) }.getOrThrow()
            assertEquals(1, platform.trashCalls)
            assertEquals(listOf(firstOwn.toRealPath()), platform.trashed)
            assertFalse(firstOwn.exists())
            assertTrue(secondOwn.isDirectory())
            assertTrue(versionFolder.resolve("1.20.1.json").isRegularFile())
            runBlocking { controller.deleteInstance(second.id) }.getOrThrow()
            assertEquals(2, platform.trashCalls)
            assertEquals(listOf(versionFolder.toRealPath(), secondOwn.toRealPath()), platform.trashed.takeLast(2))
            assertFalse(secondOwn.exists())
            assertFalse(versionFolder.exists())
            assertEquals(emptyList(), controller.store.data.instances)
        }
    }
}