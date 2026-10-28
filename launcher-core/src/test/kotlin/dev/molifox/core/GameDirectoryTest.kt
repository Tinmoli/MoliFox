// 负责验证GameDirectory的行为与边界。

package dev.molifox.core

import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import javax.tools.ToolProvider
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GameDirectoryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `managed games in external roots keep separate data across loader changes and refresh`() {
        val root = temporary.resolve("chosen minecraft").apply { createDirectories() }
        LauncherController(DesktopPlatform(), temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val first = controller.installer.createInstance(InstallRequest("first", "1.20.1"))
            val second = controller.installer.createInstance(InstallRequest("second", "1.20.1"))
            val firstPath = controller.store.instancePath(first.id)
            assertNotEquals(firstPath, controller.store.instancePath(second.id))
            firstPath.resolve("saves").createDirectories()
            firstPath.resolve("saves/test.txt").writeText("world data")
            controller.store.updateInstance(first.id) {
                it.copy(versionId = "different-loader-profile")
            }
            assertEquals(firstPath, controller.store.instancePath(first.id))
            controller.directories.refresh()
            assertTrue(controller.store.data.instances.any { it.id == first.id })
            assertEquals(
                "world data",
                controller.store.instancePath(first.id).resolve("saves/test.txt").readText(),
            )
            val legacy =
                GameInstance(
                    versionId = "old-profile",
                    gameDirectoryId = first.gameDirectoryId,
                    externalProfile = true,
                )
            assertEquals(
                root.toRealPath().resolve("versions/old-profile"),
                controller.store.gamePath(legacy),
            )
        }
    }

    @Test
    fun `empty chosen folders become install targets without creating or copying game files`() {
        val root = temporary.resolve("new minecraft").apply { createDirectories() }
        LauncherController(DesktopPlatform(), temporary.resolve("MoliFox")).use { controller ->
            assertEquals(0, controller.directories.add(root))
            assertFalse(root.resolve("versions").exists())
            val instance =
                controller.installer.createInstance(InstallRequest("first world", "1.20.1"))
            assertEquals(root.toRealPath(), controller.store.minecraftRoot(instance))
            assertTrue(controller.store.instancePath(instance.id).isDirectory())
        }
    }

    private fun version(root: Path, id: String = "fixture"): Path {
        val folder = root.resolve("versions/$id").apply { createDirectories() }
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
    fun `directory selection uses original versions without copying and refresh preserves overrides`() {
        val root = temporary.resolve("已有整合包/.minecraft")
        val folder = version(root)
        val original = folder.resolve("fixture.jar").readBytes()
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        val service = GameDirectoryService(store)
        assertEquals(1, service.add(root.parent))
        val first = store.data.instances.single()
        store.updateInstance(first.id) { it.copy(name = "我的版本", memoryMb = 2048) }
        assertEquals(root.toRealPath(), store.minecraftRoot(first))
        assertTrue(first.installed)
        assertFalse(store.minecraftRoot.resolve("versions/fixture").exists())
        service.add(root)
        assertEquals(1, store.data.instances.size)
        assertEquals(first.id, store.data.instances.single().id)
        assertEquals("我的版本", store.data.instances.single().name)
        assertEquals(2048, store.data.instances.single().memoryMb)
        version(root, "second")
        service.refresh()
        assertEquals(2, store.data.instances.size)
        assertContentEquals(original, folder.resolve("fixture.jar").readBytes())
    }

    @Test
    fun `global isolation and per-version override select the correct game and mod directories`() {
        val root = temporary.resolve(".minecraft")
        val folder = version(root)
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        GameDirectoryService(store).add(root)
        val instance = store.data.instances.single()
        assertEquals(folder.toRealPath(), store.instancePath(instance.id))
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        assertEquals(root.toRealPath(), store.instancePath(instance.id))
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Enabled) }
        assertEquals(folder.toRealPath(), store.instancePath(instance.id))
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = true)) }
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Disabled) }
        assertEquals(root.toRealPath(), store.instancePath(instance.id))
        assertEquals(
            VersionIsolation.Disabled,
            LauncherStore(DesktopPlatform(), store.dataDirectory).data.instances.single().isolation,
        )
    }

    @Test
    fun `deleting a linked version removes only its version folder and keeps the shared game`() {
        val root = temporary.resolve(".minecraft")
        val folder = version(root)
        root.resolve("saves/world").createDirectories().resolve("level.dat").writeText("my world")
        LauncherController(DesktopPlatform(), temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance = controller.store.data.instances.single()
            assertEquals(VersionIsolation.Disabled, instance.isolation)
            // 这里只验证删除范围，用永久删除避免把测试夹具放进用户系统回收站。
            // 共用游戏根、存档与其它版本必须原样保留。
            val report = runBlocking {
                controller.deleteInstance(instance.id, permanent = true)
            }
                .getOrThrow()
            assertEquals(listOf(folder.toRealPath()), report.removedPaths.map { Path.of(it) })
            assertFalse(report.movedToTrash)
            assertTrue(report.freedBytes > 0)
            assertFalse(folder.exists())
            assertTrue(root.resolve("versions").isDirectory())
            assertEquals("my world", root.resolve("saves/world/level.dat").readText())
            controller.directories.refresh()
            controller.removeGameDirectory(controller.store.data.activeGameDirectoryId!!)
            assertTrue(controller.store.data.instances.isEmpty())
            assertEquals("my world", root.resolve("saves/world/level.dat").readText())
        }
    }

    @Test
    fun `new versions use the selected directory and shared exports exclude launcher metadata`() {
        val root = temporary.resolve(".minecraft")
        version(root)
        root.resolve("saves/world").createDirectories().resolve("level.dat").writeText("my world")
        root.resolve("launcher_profiles.json").writeText("private launcher data")
        LauncherController(DesktopPlatform(), temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val original = controller.store.data.instances.single()
            val created =
                controller.installer.createInstance(InstallRequest("new version", "1.20.1"))
            assertEquals(controller.store.data.activeGameDirectoryId, created.gameDirectoryId)
            assertEquals(root.toRealPath(), controller.store.minecraftRoot(created))
            val archive = temporary.resolve("export.zip")
            controller.content.exportInstance(original.id, archive)
            ZipFile(archive.toFile()).use { zip ->
                assertNotNull(zip.getEntry("files/saves/world/level.dat"))
                assertNull(zip.getEntry("files/launcher_profiles.json"))
                assertNull(zip.getEntry("files/versions/fixture/fixture.jar"))
            }
        }
    }

    @Test
    fun `real Java launches use linked classpath and isolated or shared working directory`(): Unit =
        runBlocking {
            val root = temporary.resolve("中文 with spaces/.minecraft")
            val folder = version(root)
            val source = temporary.resolve("FixtureMinecraft.java")
            source.writeText(
                "public class FixtureMinecraft { public static void main(String[] args) { System.out.println(\"DIRECTORY_PROBE=\" + java.nio.file.Path.of(\"\").toAbsolutePath()); System.out.println(\"ARGS=\" + String.join(\"|\", args)); } }"
            )
            assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                    .run(
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
            JarOutputStream(folder.resolve("fixture.jar").outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("FixtureMinecraft.class"))
                jar.write(temporary.resolve("FixtureMinecraft.class").readBytes())
                jar.closeEntry()
            }
            LauncherController(DesktopPlatform(), temporary.resolve("MoliFox")).use { controller ->
                controller.directories.add(root)
                controller.accounts.addOffline("MoliTester")
                val instance = controller.store.data.instances.single()
                for ((isolation, expected) in
                    listOf(
                        VersionIsolation.Enabled to folder.toRealPath(),
                        VersionIsolation.Disabled to root.toRealPath(),
                    )) {
                    controller.updateInstance(
                        instance.id,
                        instance.name,
                        768,
                        Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                controller.platform.javaExecutable,
                            )
                            .toString(),
                        isolation,
                    )
                    controller.launch(instance.id)
                    withTimeout(20_000) {
                        controller.jobs.awaitIdle()
                        while (controller.state.value.runningInstanceId != null) delay(20)
                    }
                    val logs = controller.state.value.logs.map { it.message }
                    assertTrue(logs.contains("DIRECTORY_PROBE=$expected"), logs.joinToString("\n"))
                    assertTrue(
                        logs.any { it.contains("ARGS=--username|MoliTester|--gameDir|$expected") }
                    )
                }
            }
        }
}
