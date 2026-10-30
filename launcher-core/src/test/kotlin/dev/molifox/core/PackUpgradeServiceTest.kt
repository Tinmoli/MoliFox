// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包升级的服务接线：陈旧预览、组件变化与升级落地。

package dev.molifox.core

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 预览校验、组件切换授权与升级后的文件/账本状态。 */
class PackUpgradeServiceTest : PackUpgradeTestBase() {
    @Test
    fun `a stale upgrade preview is rejected before touching files`() =
        runWithService { store, service ->
            val instance = instance(store, exportedPack(emptyMap()).toString())
            val root = store.instancePath(instance.id)
            PackOwnershipStore.write(root, ledger(versionId = "1.0.0"))
            val next = exportedPack(mapOf("config/a.txt" to "a".toByteArray()))
            val error =
                assertFailsWith<IllegalArgumentException> {
                    service.upgradePack(
                        instance.id,
                        next,
                        "0".repeat(64),
                        false,
                        JobContext(store, "fixture"),
                    )
                }
            assertContains(error.message.orEmpty(), "升级计划已过期")
            assertFalse(root.resolve("config/a.txt").exists())
            assertEquals(instance.packFile, store.instance(instance.id).packFile)
        }

    @Test
    fun `component changes need explicit approval`() =
        runWithService { store, service ->
            val instance = instance(store, exportedPack(emptyMap()).toString())
            val root = store.instancePath(instance.id)
            PackOwnershipStore.write(
                root,
                ledger(versionId = "1.0.0", gameVersion = "1.20.1", loader = "Vanilla"),
            )
            val next =
                exportedPack(mapOf("config/a.txt" to "a".toByteArray()), gameVersion = "1.21")
            val plan = service.previewPackUpgrade(instance.id, next)
            assertTrue(plan.requiresComponentChange)
            val error =
                assertFailsWith<IllegalArgumentException> {
                    service.upgradePack(
                        instance.id,
                        next,
                        plan.archiveSha256,
                        false,
                        JobContext(store, "fixture"),
                    )
                }
            assertContains(error.message.orEmpty(), "需先切换组件再升级")
            assertFalse(root.resolve("config/a.txt").exists())

            service.upgradePack(
                instance.id,
                next,
                plan.archiveSha256,
                true,
                JobContext(store, "fixture"),
            )
            assertEquals("a", root.resolve("config/a.txt").readText())
        }

    @Test
    fun `upgrade rewrites pack files, keeps user edits and records the new ledger`() =
        runWithService { store, service ->
            val (instance, root) = prepareUpgradedInstance(store)
            val next =
                exportedPack(
                    mapOf(
                        "config/fixture.txt" to "new config".toByteArray(),
                        "config/keep.txt" to "pack keep v2".toByteArray(),
                        "config/added.txt" to "added".toByteArray(),
                    ),
                )
            val plan = service.previewPackUpgrade(instance.id, next)
            assertUpgradePlan(plan)

            val result =
                service.upgradePack(
                    instance.id,
                    next,
                    plan.archiveSha256,
                    false,
                    JobContext(store, "fixture"),
                )
            assertEquals(plan.changes, result.changes)
            assertUpgradedInstance(store, instance, root, next)
        }

    /** 旧整合包：包内两份文件、一份被用户改动、一份已过期，并写入 1.0.0 账本。 */
    private fun prepareUpgradedInstance(store: LauncherStore): Pair<GameInstance, Path> {
        val old = "old config".toByteArray()
        val packKeep = "pack keep".toByteArray()
        val edited = "user edited".toByteArray()
        val removed = "old removed mod".toByteArray()
        val oldPack =
            exportedPack(
                mapOf(
                    "config/fixture.txt" to old,
                    "config/keep.txt" to packKeep,
                    "mods/removed.jar" to removed,
                ),
            )
        val instance = instance(store, oldPack.toString())
        val root = store.instancePath(instance.id)
        root.resolve("config/fixture.txt").apply {
            parent.createDirectories()
            writeBytes(old)
        }
        root.resolve("config/keep.txt").writeBytes(edited)
        root.resolve("mods/removed.jar").apply {
            parent.createDirectories()
            writeBytes(removed)
        }
        PackOwnershipStore.write(
            root,
            ledger(
                "config/fixture.txt" to old,
                "config/keep.txt" to packKeep,
                "mods/removed.jar" to removed,
                versionId = "1.0.0",
            ),
        )
        return instance to root
    }

    /** 预览应替换包内文件、保护用户改动、移除过期文件并新增文件。 */
    private fun assertUpgradePlan(plan: PackUpgradePlan) {
        assertEquals(
            PackFileAction.Replace,
            plan.changes.single { it.path == "config/fixture.txt" }.action,
        )
        val kept = plan.changes.single { it.path == "config/keep.txt" }
        assertEquals(PackFileAction.Keep, kept.action)
        assertEquals(PackUserState.UserModified, kept.userState)
        assertEquals(listOf("config/keep.txt"), plan.protectedPaths)
        assertEquals(
            PackFileAction.Remove,
            plan.changes.single { it.path == "mods/removed.jar" }.action,
        )
        assertEquals(
            PackFileAction.Add,
            plan.changes.single { it.path == "config/added.txt" }.action,
        )
    }

    /** 升级后：新文件落地、用户改动保留、过期文件删除、档案与账本指向新版本。 */
    private fun assertUpgradedInstance(
        store: LauncherStore,
        instance: GameInstance,
        root: Path,
        next: Path,
    ) {
        assertEquals("new config", root.resolve("config/fixture.txt").readText())
        assertEquals("user edited", root.resolve("config/keep.txt").readText())
        assertFalse(root.resolve("mods/removed.jar").exists())
        assertEquals("added", root.resolve("config/added.txt").readText())

        val updated = store.instance(instance.id)
        assertTrue(updated.installed)
        val saved = Path.of(updated.packFile)
        assertTrue(saved.startsWith(store.directories.packArchives))
        assertEquals(digest(next, "SHA-256"), digest(saved, "SHA-256"))
        val ownership = PackOwnershipStore.read(root, store)
        assertNotNull(ownership)
        assertEquals(digest(next, "SHA-256"), ownership.archiveSha256)
        assertEquals(
            setOf("config/added.txt", "config/fixture.txt", "config/keep.txt"),
            ownership.files.map { it.path }.toSet(),
        )
        assertTrue(root.listDirectoryEntries(".molifox-pack-upgrade-*").isEmpty())
        assertTrue(root.listDirectoryEntries(".molifox-pack-*").none { it.isDirectory() })
        assertEquals("Fixture pack", ownership.name)
    }
}
