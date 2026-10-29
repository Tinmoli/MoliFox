// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例备份目录的过滤规则的行为与边界。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstanceBackupFilterTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `save backups are not copied or classified as unknown pack files`() = runBlocking {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        val instance =
            GameInstance(
                name = "source",
                gameVersion = "1.20.1",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id).apply { createDirectories() }
        val backup =
            root.resolve(".molifox-saves-backups/world.zip").apply {
                parent.createDirectories()
                writeText("backup")
            }
        PackOwnershipStore.write(root, PackOwnership(gameVersion = "1.20.1", files = emptyList()))
        val tools = InstanceTools(store)
        assertTrue(tools.verifyInstance(instance.id).unknownFiles.isEmpty())
        val copy = tools.copyInstance(instance.id, "copy", context = JobContext(store, "test"))
        assertFalse(store.instancePath(copy.id).resolve(".molifox-saves-backups").exists())
        assertEquals("backup", backup.readText())
    }
}
