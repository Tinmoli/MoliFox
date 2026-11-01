// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 删除实例后，selectedInstanceId 不能残留任何无法再解析到实例的旧标识，否则界面读取会抛异常并崩溃。
//
// resolveInstance 接受「规范 id / 旧 id 别名 / 目录名 / 显示名」四种形式，所以选中项里可能是
// 后三种之一；清理时只拿它和 instance.id 比字符串就会漏掉这些值。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceDeletionAliasTest {
    @TempDir
    lateinit var temporary: Path

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

    /** 造一个可被扫描的版本目录与客户端 jar。 */
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
    fun `deleting an instance selected by its folder name clears the stale id`() {
        val root = temporary.resolve(".minecraft")
        root.createDirectories()
        version(root, "1.20.1")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance =
                controller.store.data.instances
                    .first()

            // 选中项用目录名（旧配置/旧调用方保存的形式）：它不等于规范 id，但能解析到同一实例。
            val folderName = instance.instanceSettingsKey()
            controller.store.mutate { it.copy(selectedInstanceId = folderName) }
            assertEquals(instance.id, controller.store.instance(folderName).id)

            val report = runBlocking { controller.deleteInstance(folderName) }.getOrThrow()
            assertTrue(report.removedPaths.isNotEmpty())

            val selected = controller.store.data.selectedInstanceId
            assertTrue(
                selected == null || runCatching { controller.store.instance(selected) }.isSuccess,
                "选中项必须仍能解析，不能残留已删除实例的旧标识：$selected",
            )
            assertTrue(
                controller.store.data.instances
                    .none { it.id == instance.id },
            )
        }
    }

    @Test
    fun `deleting a later instance keeps a selection that still resolves`() {
        // 选中项指向实例 A，删掉的却是实例 B（B 经别名解析）：A 的选中必须保留，不能误清。
        val root = temporary.resolve(".minecraft")
        root.createDirectories()
        version(root, "1.20.1")
        version(root, "1.21")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instances = controller.store.data.instances
            assertTrue(instances.size >= 2, "测试前提：至少两个实例")
            val keep = instances[0]
            val remove = instances[1]
            controller.store.mutate { it.copy(selectedInstanceId = keep.instanceSettingsKey()) }

            val report =
                runBlocking {
                    controller.deleteInstance(remove.instanceSettingsKey())
                }.getOrThrow()
            assertTrue(report.removedPaths.isNotEmpty())

            val selected = controller.store.data.selectedInstanceId
            assertEquals(keep.id, controller.store.instance(requireNotNull(selected)).id)
            assertTrue(
                controller.store.data.instances
                    .any { it.id == keep.id },
            )
            assertTrue(
                controller.store.data.instances
                    .none { it.id == remove.id },
            )
        }
    }

    @Test
    fun `queries with a deleted id return nothing instead of throwing`() {
        // 界面在删除后仍可能带着旧 id 跑一次目录占用查询：必须按「查不到」处理。
        // 抛异常会冒泡到 Compose 的未捕获异常处理器，弹出 Error 对话框并中止界面。
        val root = temporary.resolve(".minecraft")
        root.createDirectories()
        version(root, "1.20.1")
        val platform = TrashPlatform(temporary.resolve("trash"))
        LauncherController(platform, temporary.resolve("MoliFox")).use { controller ->
            controller.directories.add(root)
            val instance =
                controller.store.data.instances
                    .first()
            val displayedPath = controller.store.gamePath(instance)
            val resources = taskResourcesForInstances(controller.store, instance.id)
            assertFalse(controller.isInstanceBusy(instance.id))
            runBlocking { controller.deleteInstance(instance.id) }.getOrThrow()

            assertTrue(
                runCatching { controller.store.instance(instance.id) }.isFailure,
            )
            assertNull(runBlocking { controller.instanceDirectorySize(instance.id) })
            assertTrue(controller.isInstanceBusy(instance.id))
            assertTrue(controller.isInstanceBusy(instance.instanceSettingsKey()))
            assertEquals(resources, taskResourcesForInstance(controller.store, instance.id, instance))
            // 已退出的页面仍可用旧快照完成一次只读路径计算，不再重新查已删除的 id。
            assertEquals(displayedPath, controller.store.gamePath(instance))
        }
    }

    @Test
    fun `snapshot resources preserve caller aliases and shared directory conflicts`() {
        LauncherController(TrashPlatform(temporary.resolve("trash")), temporary.resolve("MoliFox")).use { controller ->
            val first = GameInstance(name = "First", versionId = "1.20.1", gameVersion = "1.20.1")
            val second = first.copy(id = "second", name = "Second")
            controller.store.mutate { it.copy(instances = listOf(first, second)) }
            val alias = first.instanceSettingsKey()
            val actual = taskResourcesForInstances(controller.store, alias)
            val expected =
                linkedSetOf(
                    "instance:$alias",
                    "files:${taskPathKey(controller.store.gamePath(first))}",
                    "version:${taskPathKey(controller.store.minecraftRoot(first).resolve("versions/1.20.1"))}",
                )
            assertEquals(expected.toList(), actual.toList())
            assertEquals(actual, taskResourcesForInstance(controller.store, alias, first))
            assertTrue(taskResourcesConflict(actual, taskResourcesForInstances(controller.store, second.id)))
            controller.store.mutate { it.copy(instances = emptyList()) }
            assertNull(controller.store.instanceOrNull(alias))
            assertEquals(actual, taskResourcesForInstance(controller.store, alias, first))
            assertTrue(runCatching { controller.requireIdle(alias) }.isFailure)
        }
    }
}
