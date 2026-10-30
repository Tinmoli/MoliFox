// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包升级计划与受保护文件的规则。

package dev.molifox.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 规划规则 1–9：新增/替换/移除/保留、用户改动保护与组件变化。 */
class PackUpgradeTest : PackUpgradeTestBase() {
    @Test
    fun `missing files are added while deleted ones stay missing unless policy restores them`() {
        val old = ledger("config/deleted.txt" to "old".toByteArray(), versionId = "1.0.0")
        val new =
            ledger(
                "config/deleted.txt" to "new".toByteArray(),
                "config/fresh.txt" to "fresh".toByteArray(),
                versionId = "2.0.0",
            )
        val plan = PackUpgradePlanner.plan("instance", old, new, emptyMap())
        val deleted = plan.changes.single { it.path == "config/deleted.txt" }
        assertEquals(PackFileAction.Keep, deleted.action)
        assertEquals(PackUserState.UserDeleted, deleted.userState)
        val fresh = plan.changes.single { it.path == "config/fresh.txt" }
        assertEquals(PackFileAction.Add, fresh.action)
        assertEquals(PackUserState.Unchanged, fresh.userState)

        val restored =
            PackUpgradePlanner.plan(
                "instance",
                old,
                new,
                emptyMap(),
                PackUpgradePolicy(restoreUserDeleted = true),
            )
        val restore = restored.changes.single { it.path == "config/deleted.txt" }
        assertEquals(PackFileAction.Add, restore.action)
        assertEquals(PackUserState.UserDeleted, restore.userState)
    }

    @Test
    fun `files already matching the new hash are kept`() {
        val data = "same".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/a.jar" to data, versionId = "1.0.0"),
                ledger("mods/a.jar" to data, versionId = "2.0.0"),
                mapOf("mods/a.jar" to PackFileState(true, sha(data))),
            )
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.Unchanged, change.userState)
        assertTrue(plan.protectedPaths.isEmpty())
    }

    @Test
    fun `files still matching the old ledger are replaced`() {
        val old = "old".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/a.jar" to old, versionId = "1.0.0"),
                ledger("mods/a.jar" to "new".toByteArray(), versionId = "2.0.0"),
                mapOf("mods/a.jar" to PackFileState(true, sha(old))),
            )
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Replace, change.action)
        assertEquals(PackUserState.Unchanged, change.userState)
    }

    @Test
    fun `user modified files are protected by default and replaced when protection is off`() {
        val old = "old".toByteArray()
        val edited = "user edited".toByteArray()
        val oldLedger = ledger("mods/a.jar" to old, versionId = "1.0.0")
        val newLedger = ledger("mods/a.jar" to "new".toByteArray(), versionId = "2.0.0")
        val current = mapOf("mods/a.jar" to PackFileState(true, sha(edited)))
        val plan = PackUpgradePlanner.plan("instance", oldLedger, newLedger, current)
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserModified, change.userState)
        assertEquals(listOf("mods/a.jar"), plan.protectedPaths)

        val forced =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                newLedger,
                current,
                PackUpgradePolicy(protectUserModified = false),
            )
        assertEquals(PackFileAction.Replace, forced.changes.single().action)
        assertEquals(PackUserState.UserModified, forced.changes.single().userState)
        assertTrue(forced.protectedPaths.isEmpty())
    }

    @Test
    fun `obsolete files are removed and can be kept by policy`() {
        val old = "old".toByteArray()
        val oldLedger = ledger("mods/old.jar" to old, versionId = "1.0.0")
        val current = mapOf("mods/old.jar" to PackFileState(true, sha(old)))
        val plan =
            PackUpgradePlanner.plan("instance", oldLedger, ledger(versionId = "2.0.0"), current)
        assertEquals(PackFileAction.Remove, plan.changes.single().action)
        assertEquals(PackUserState.Unchanged, plan.changes.single().userState)

        val kept =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                ledger(versionId = "2.0.0"),
                current,
                PackUpgradePolicy(removeObsoleteFiles = false),
            )
        assertEquals(PackFileAction.Keep, kept.changes.single().action)
    }

    @Test
    fun `modified obsolete files are protected but removed when protection is off`() {
        val oldLedger = ledger("mods/old.jar" to "old".toByteArray(), versionId = "1.0.0")
        val current = mapOf("mods/old.jar" to PackFileState(true, sha("user edited".toByteArray())))
        val plan =
            PackUpgradePlanner.plan("instance", oldLedger, ledger(versionId = "2.0.0"), current)
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserModified, change.userState)
        assertEquals(listOf("mods/old.jar"), plan.protectedPaths)

        val forced =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                ledger(versionId = "2.0.0"),
                current,
                PackUpgradePolicy(protectUserModified = false),
            )
        assertEquals(PackFileAction.Remove, forced.changes.single().action)
        assertEquals(PackUserState.UserModified, forced.changes.single().userState)
    }

    @Test
    fun `obsolete files the user already deleted stay deleted`() {
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/old.jar" to "old".toByteArray(), versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                emptyMap(),
            )
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserDeleted, change.userState)
    }

    @Test
    fun `files neither pack owns are treated as user additions and never deleted`() {
        val data = "user file".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                mapOf("mods/user.jar" to PackFileState(true, sha(data))),
                PackUpgradePolicy(removeObsoleteFiles = true, protectUserModified = false),
            )
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserAdded, change.userState)
    }

    @Test
    fun `game version or loader changes require a component change`() {
        val same =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                emptyMap(),
            )
        assertFalse(same.requiresComponentChange)

        val gameVersion =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0", gameVersion = "1.20.1"),
                ledger(versionId = "2.0.0", gameVersion = "1.21"),
                emptyMap(),
            )
        assertTrue(gameVersion.requiresComponentChange)

        val loader =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0", loader = "Vanilla"),
                ledger(versionId = "2.0.0", loader = "Fabric", loaderVersion = "0.15.0"),
                emptyMap(),
            )
        assertTrue(loader.requiresComponentChange)
    }

    @Test
    fun `plan counts every action in a mixed upgrade`() {
        val keep = "keep".toByteArray()
        val replace = "replace".toByteArray()
        val removed = "removed".toByteArray()
        val added = "added".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger(
                    "keep.txt" to keep,
                    "replace.txt" to replace,
                    "removed.txt" to removed,
                    versionId = "1.0.0",
                ),
                ledger(
                    "keep.txt" to keep,
                    "replace.txt" to "replace-v2".toByteArray(),
                    "added.txt" to added,
                    versionId = "2.0.0",
                ),
                mapOf(
                    "keep.txt" to PackFileState(true, sha(keep)),
                    "replace.txt" to PackFileState(true, sha(replace)),
                    "removed.txt" to PackFileState(true, sha(removed)),
                ),
            )
        assertEquals(1, plan.adds)
        assertEquals(1, plan.replaces)
        assertEquals(1, plan.removes)
        assertEquals(1, plan.keeps)
        assertEquals("1.0.0", plan.fromVersionId)
        assertEquals("2.0.0", plan.toVersionId)
    }
}
