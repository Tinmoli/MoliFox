// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例删除时「版本目录被真实共用」的拒绝路径，以及启动发现清理失效记录。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 版本目录被另一个实例真实引用时的拒绝原因；日志、预检与异常消息必须是同一句。 */
private fun sharedVersionMessage(holder: String) = "无法删除：版本目录仍被实例「$holder」使用；请先处理该实例"

/** 共用版本目录与启动发现：验证真实 inheritsFrom 引用会整次拒绝删除。 */
class InstanceDeletionInheritanceTest {
    @TempDir lateinit var temporary: Path

    /** 记录 `moveToTrash` 的假平台：把目标移到临时“回收站”，便于断言到底交给了谁。 */
    private class TrashPlatform(
        private val bin: Path,
    ) : PlatformServices {
        var trashCalls = 0
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

    /** 与 [target] 游戏版本字符串相同、但版本目录不同的另一个实例记录。 */
    private fun sameVersionInstance(target: GameInstance) =
        GameInstance(
            name = "TwilightMC-26.3v1.0",
            gameVersion = "26.3",
            versionId = "TwilightMC-26.3v1.0",
            gameDirectoryName = "TwilightMC-26.3v1.0",
            gameDirectoryId = target.gameDirectoryId,
        )

    /** 两个实例互相 inheritsFrom 的版本配置，各自带上要保留的内容。 */
    private fun writeMutualInheritance(
        firstOwn: Path,
        secondOwn: Path,
    ) {
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
    }

    /** 删除被 [holder] 真实占用的版本目录：必须整次拒绝，并给出同一句原因。 */
    private fun assertDeletionBlockedBy(
        controller: LauncherController,
        instanceId: String,
        holder: String,
    ) {
        val failure = runBlocking { controller.deleteInstance(instanceId) }.exceptionOrNull()
        assertIs<InstanceDeletionBlockedException>(failure)
        assertEquals(sharedVersionMessage(holder), failure.message)
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
                it.copy(instances = it.instances + sameVersionInstance(target))
            }
            val reason = sharedVersionMessage("TwilightMC-26.3v1.0")
            assertTrue(controller.deletionTargets(target.id).isEmpty())
            assertEquals(reason, controller.deletionBlockReason(target.id))
            val failure =
                runBlocking { controller.deleteInstance(target.id, permanent = true) }
                    .exceptionOrNull()
            assertIs<InstanceDeletionBlockedException>(failure)
            assertEquals(reason, failure.message)
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
            assertTrue(messages.any { it == reason }, "日志必须显示同一条拒绝原因：$messages")
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
            writeMutualInheritance(firstOwn, secondOwn)
            assertDeletionBlockedBy(controller, first.id, "第二个")
            // 整次拒绝：不删版本目录、不删自己的游戏目录、不移回收站、记录还在。
            assertEquals(0, platform.trashCalls)
            assertTrue(firstOwn.resolve("第一个.json").isRegularFile())
            assertEquals("mod", firstOwn.resolve("mods/a.jar").readText())
            assertEquals("save", secondOwn.resolve("saves/level.dat").readText())
            assertEquals(2, controller.store.data.instances.size)
            // 反过来也一样：占用者还在，第二名实例同样删不掉。
            assertDeletionBlockedBy(controller, second.id, "第一个")
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
