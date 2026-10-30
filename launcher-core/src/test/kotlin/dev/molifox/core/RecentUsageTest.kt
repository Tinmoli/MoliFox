// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证世界和服务器最近使用时间的本地记录的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentUsageTest {
    private val instance = "11111111-1111-1111-1111-111111111111"
    private val other = "22222222-2222-2222-2222-222222222222"

    @Test
    fun `record keeps newest first per kind and survives restart`() =
        withTempDirectory("molifox-recent-usage-") { directory ->
            val store = LauncherStore(DesktopPlatform(), directory)
            var now = Instant.parse("2026-10-28T05:00:00Z")
            val usage = RecentUsage(store) { now }

            usage.record(EntryKind.World, instance, "world-a")
            now = now.plusSeconds(10)
            usage.record(EntryKind.Server, instance, "mc.example.com")
            now = now.plusSeconds(10)
            usage.record(EntryKind.World, instance, "world-b")

            assertEquals(directory.resolve("cache/recent-usage.json"), usage.file)
            assertEquals(listOf("world-b", "world-a"), usage.recent(EntryKind.World, instance, 10))
            assertEquals(listOf("mc.example.com"), usage.recent(EntryKind.Server, instance, 10))
            assertEquals(
                Instant.parse("2026-10-28T05:00:00Z"),
                usage.lastUsed(EntryKind.World, instance, "world-a"),
            )
            assertNull(usage.lastUsed(EntryKind.World, instance, "missing"))
            assertTrue(usage.recent(EntryKind.World, other, 10).isEmpty())
            assertEquals(listOf("world-b"), usage.recent(EntryKind.World, instance, 1))
            assertTrue(usage.recent(EntryKind.World, instance, 0).isEmpty())

            // 再次使用旧存档只更新时间并移到最前，不产生重复记录。
            now = now.plusSeconds(10)
            usage.record(EntryKind.World, instance, "world-a")
            assertEquals(listOf("world-a", "world-b"), usage.recent(EntryKind.World, instance, 10))

            val restarted = RecentUsage(store)
            assertEquals(
                listOf("world-a", "world-b"),
                restarted.recent(EntryKind.World, instance, 10),
            )
            assertEquals(now, restarted.lastUsed(EntryKind.World, instance, "world-a"))
        }

    @Test
    fun `per instance cap keeps only the newest hundred entries`() =
        withTempDirectory("molifox-recent-usage-") { directory ->
            val store = LauncherStore(DesktopPlatform(), directory)
            var now = Instant.parse("2026-10-28T05:00:00Z")
            val usage = RecentUsage(store) { now }
            repeat(105) { index ->
                usage.record(EntryKind.World, instance, "world-$index")
                now = now.plusSeconds(1)
            }

            val keys = usage.recent(EntryKind.World, instance, 500)
            assertEquals(100, keys.size)
            assertEquals("world-104", keys.first())
            assertEquals("world-5", keys.last())
            assertFalse("world-4" in keys)

            usage.record(EntryKind.World, other, "other-instance")
            assertEquals(listOf("other-instance"), usage.recent(EntryKind.World, other, 10))
        }

    @Test
    fun `corrupt file starts fresh and the next record repairs it`() =
        withTempDirectory("molifox-recent-usage-") { directory ->
            val store = LauncherStore(DesktopPlatform(), directory)
            val usage = RecentUsage(store)
            usage.record(EntryKind.World, instance, "world-a")
            usage.file.writeText("{broken")

            assertTrue(usage.recent(EntryKind.World, instance, 10).isEmpty())
            assertNull(usage.lastUsed(EntryKind.World, instance, "world-a"))

            usage.record(EntryKind.Server, instance, "mc.example.com")
            assertEquals(listOf("mc.example.com"), usage.recent(EntryKind.Server, instance, 10))
            assertTrue(usage.recent(EntryKind.World, instance, 10).isEmpty())
            val repaired = gson.fromJson(usage.file.readText(), Map::class.java)
            assertEquals(1, (repaired["entries"] as List<*>).size)
        }

    @Test
    fun `forget removes only the requested entry and is safe when absent`() =
        withTempDirectory("molifox-recent-usage-") { directory ->
            val store = LauncherStore(DesktopPlatform(), directory)
            val usage = RecentUsage(store)
            usage.record(EntryKind.World, instance, "world-a")
            usage.record(EntryKind.World, instance, "world-b")

            usage.forget(EntryKind.World, instance, "world-a")
            assertEquals(listOf("world-b"), usage.recent(EntryKind.World, instance, 10))
            assertNull(usage.lastUsed(EntryKind.World, instance, "world-a"))

            // 删除不存在或重复删除都不应抛异常。
            usage.forget(EntryKind.World, instance, "world-a")
            usage.forget(EntryKind.Server, instance, "never-recorded")
            assertEquals(listOf("world-b"), usage.recent(EntryKind.World, instance, 10))
        }

    @Test
    fun `pruneMissing keeps existing keys and removes stale ones`() =
        withTempDirectory("molifox-recent-usage-") { directory ->
            val store = LauncherStore(DesktopPlatform(), directory)
            var now = Instant.parse("2026-10-28T05:00:00Z")
            val usage = RecentUsage(store) { now }
            usage.record(EntryKind.World, instance, "world-a")
            now = now.plusSeconds(10)
            usage.record(EntryKind.World, instance, "world-b")
            now = now.plusSeconds(10)
            usage.record(EntryKind.World, instance, "world-c")
            now = now.plusSeconds(10)
            usage.record(EntryKind.Server, instance, "mc.example.com")
            now = now.plusSeconds(10)
            usage.record(EntryKind.World, other, "other-world")

            // world-b 已不在磁盘上：只删它，其它实例与服务器记录都不受 kind/instance 之外的键影响。
            assertEquals(
                1,
                usage.pruneMissing(EntryKind.World, instance, setOf("world-a", "world-c")),
            )
            assertEquals(listOf("world-c", "world-a"), usage.recent(EntryKind.World, instance, 10))
            assertEquals(listOf("mc.example.com"), usage.recent(EntryKind.Server, instance, 10))
            assertEquals(listOf("other-world"), usage.recent(EntryKind.World, other, 10))

            // 清理结果已写回文件：换一个实例读同一份文件仍是清理后的内容。
            val reloaded = RecentUsage(store)
            assertEquals(
                listOf("world-c", "world-a"),
                reloaded.recent(EntryKind.World, instance, 10),
            )
            assertNull(reloaded.lastUsed(EntryKind.World, instance, "world-b"))
            val persisted = gson.fromJson(usage.file.readText(), Map::class.java)
            assertEquals(4, (persisted["entries"] as List<*>).size)

            assertPruningDoesNotWrite(usage)
        }

    private fun assertPruningDoesNotWrite(usage: RecentUsage) {
        // 全部存在时是纯读：不写文件，内容与修改时间都不变。
        val before = usage.file.readText()
        val stamp = usage.file.getLastModifiedTime()
        assertEquals(
            0,
            usage.pruneMissing(EntryKind.World, instance, setOf("world-a", "world-c", "never")),
        )
        assertEquals(
            0,
            usage.pruneMissing(EntryKind.Server, instance, setOf("mc.example.com")),
        )
        assertEquals(before, usage.file.readText())
        assertEquals(stamp, usage.file.getLastModifiedTime())
    }
}
