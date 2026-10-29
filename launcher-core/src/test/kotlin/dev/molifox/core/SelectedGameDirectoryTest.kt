// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证默认目录按真实路径唯一登记，当前选择决定新安装实例的持久落点。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SelectedGameDirectoryTest {
    @TempDir lateinit var directory: Path

    private fun version(root: Path) {
        val folder = root.resolve("versions/26.3").apply { createDirectories() }
        writeJson(
            folder.resolve("26.3.json"),
            mapOf("id" to "26.3", "mainClass" to "fixture.Main", "libraries" to emptyList<Any>()),
        )
        folder.resolve("26.3.jar").writeBytes(byteArrayOf())
    }

    @Test
    fun `selecting the default physical path never creates another directory identity`() {
        val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
        val root = directory.resolve("games").apply { createDirectories() }
        store.mutate { it.copy(settings = it.settings.copy(gameRoot = root.toString())) }
        version(root)
        val service = GameDirectoryService(store)
        assertEquals(1, service.add(root))
        assertNull(store.data.activeGameDirectoryId)
        assertTrue(store.data.gameDirectories.isEmpty())
        assertNull(store.data.instances.single().gameDirectoryId)
        service.add(root.resolve("."))
        service.refresh()
        assertEquals(1, store.data.instances.size)
        assertTrue(store.data.gameDirectories.isEmpty())
    }

    @Test
    fun `selected external directory is used by new vanilla and loader instances and survives later selection`() {
        LauncherController(dataDirectory = directory.resolve("profile")).use { controller ->
            val a = directory.resolve("external-a").apply { createDirectories() }
            val b = directory.resolve("external-b").apply { createDirectories() }
            controller.directories.add(a)
            val firstId = assertNotNull(controller.store.data.activeGameDirectoryId)
            val vanilla = controller.installer.createInstance(InstallRequest("Vanilla", "26.3"))
            val fabric =
                controller.installer.createInstance(
                    InstallRequest("Fabric", "26.3", LoaderType.Fabric, "0.20.1")
                )
            assertEquals(firstId, vanilla.gameDirectoryId)
            assertEquals(firstId, fabric.gameDirectoryId)
            controller.directories.add(b)
            val other = controller.installer.createInstance(InstallRequest("Other", "26.3"))
            assertEquals(a.toRealPath(), controller.store.minecraftRoot(vanilla))
            assertEquals(a.toRealPath(), controller.store.minecraftRoot(fabric))
            assertEquals(b.toRealPath(), controller.store.minecraftRoot(other))
            assertFalse(controller.store.minecraftRoot.resolve("versions/Vanilla").exists())
            controller.directories.select(firstId)
            controller.directories.refresh()
            assertEquals(2, controller.store.data.instances.count { it.gameDirectoryId == firstId })
        }
    }
}
