// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例隔离切换与跨游戏目录迁移的行为与边界。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IsolationMigrationTest {
    @TempDir lateinit var temporary: Path

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    /** 建一个实例：isolate=true 用版本隔离（独占目录），false 共用游戏目录。 */
    private fun instance(
        store: LauncherStore,
        name: String = "Fixture",
        isolate: Boolean = true,
    ): GameInstance {
        val instance =
            GameInstance(
                name = name,
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                gameDirectoryName = name,
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                managedGameDirectory = isolate,
                isolation = if (isolate) VersionIsolation.Enabled else VersionIsolation.Disabled,
            )
        store.mutate {
            it.copy(
                instances = it.instances + instance,
                settings = it.settings.copy(isolateVersions = isolate),
            )
        }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun sharedRoot(store: LauncherStore) = Path.of(store.data.settings.gameRoot)

    private fun isolatedRoot(store: LauncherStore, instance: GameInstance) =
        store.versionGamePath(instance)

    // ---- 计划 ----

    @Test
    fun `plan only lists whitelisted game content`() {
        val store = store()
        val shared = instance(store, isolate = false)
        val root = store.instancePath(shared.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("config/fixture.toml"), "config")
        write(root.resolve("defaultconfigs/pack.toml"), "default")
        write(root.resolve("options.txt"), "options")
        write(root.resolve("servers.dat"), "servers")
        write(root.resolve("resourcepacks/pack.zip"), "pack")
        write(root.resolve("shaderpacks/shader.zip"), "shader")
        write(root.resolve("logs/latest.log"), "log")
        write(root.resolve("crash-reports/crash.txt"), "crash")
        write(root.resolve("caches/one.bin"), "cache")
        write(root.resolve("screenshots/a.png"), "png")
        write(root.resolve("usercache.json"), "cache")
        write(root.resolve(".molifox-content.json"), "{}")
        write(root.resolve(".molifox-pack-upgrade-fixture/files/0/a.jar"), "backup")

        val plan = IsolationMigration(store).planIsolation(shared.id, isolate = true)

        assertEquals(
            listOf(
                "config/fixture.toml",
                "defaultconfigs/pack.toml",
                "mods/a.jar",
                "options.txt",
                "resourcepacks/pack.zip",
                "servers.dat",
                "shaderpacks/shader.zip",
            ),
            plan.entries.map { it.relativePath },
        )
        assertEquals(IsolationMode.Shared, plan.from)
        assertEquals(IsolationMode.Isolated, plan.to)
        assertTrue(plan.blockers.isEmpty())
        assertEquals(7, plan.transferCount)
    }

    @Test
    fun `plan reports conflicting targets and refuses to overwrite`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val shared = instance(store, isolate = false)
        val root = store.instancePath(shared.id)
        write(root.resolve("mods/a.jar"), "mine")
        val target = isolatedRoot(store, shared)
        write(target.resolve("mods/a.jar"), "theirs")

        val plan = migration.planIsolation(shared.id, isolate = true)

        assertEquals(1, plan.conflictCount)
        assertEquals(MigrationAction.Conflict, plan.entries.single().action)
        assertEquals(1, plan.blockers.size)
        assertTrue(plan.blockers.single().contains("mods/a.jar"))

        val error =
            assertFailsWith<IllegalArgumentException> {
                migration.changeIsolation(
                    shared.id,
                    isolate = true,
                    context = JobContext(store, "fixture"),
                )
            }
        assertTrue(error.message.orEmpty().contains("mods/a.jar"))
        assertEquals("theirs", target.resolve("mods/a.jar").readText())
        assertEquals("mine", root.resolve("mods/a.jar").readText())
        assertEquals(VersionIsolation.Disabled, store.instance(shared.id).isolation)
    }

    @Test
    fun `plan skips targets that already hold identical content`() {
        val store = store()
        val shared = instance(store, isolate = false)
        val root = store.instancePath(shared.id)
        write(root.resolve("mods/a.jar"), "same")
        write(isolatedRoot(store, shared).resolve("mods/a.jar"), "same")

        val plan = IsolationMigration(store).planIsolation(shared.id, isolate = true)

        assertEquals(MigrationAction.Skip, plan.entries.single().action)
        assertEquals(0L, plan.copyBytes)
        assertEquals(0, plan.transferCount)
        assertTrue(plan.blockers.isEmpty())
    }

    @Test
    fun `plan honours the copySaves switch`() {
        val store = store()
        val shared = instance(store, isolate = false)
        val root = store.instancePath(shared.id)
        write(root.resolve("saves/world/level.dat"), "level")

        val withSaves = IsolationMigration(store).planIsolation(shared.id, isolate = true)
        assertEquals(listOf("saves/world/level.dat"), withSaves.entries.map { it.relativePath })
        assertEquals("level".length.toLong(), withSaves.copyBytes)

        val withoutSaves =
            IsolationMigration(store).planIsolation(shared.id, isolate = true, copySaves = false)
        val entry = withoutSaves.entries.single()
        assertEquals("saves", entry.relativePath)
        assertEquals(MigrationAction.Skip, entry.action)
        assertEquals("level".length.toLong(), entry.size)
        assertTrue(entry.reason.isNotBlank())
        assertEquals(0, withoutSaves.transferCount)
        assertTrue(withoutSaves.blockers.isEmpty())
    }

    @Test
    fun `plan omits paths that do not exist in the source`() {
        val store = store()
        val shared = instance(store, isolate = false)

        val empty = IsolationMigration(store).planIsolation(shared.id, isolate = true)

        assertTrue(empty.entries.isEmpty())
        assertEquals(0L, empty.copyBytes)
        assertTrue(empty.blockers.isEmpty())

        write(store.instancePath(shared.id).resolve("options.txt"), "options")
        val one = IsolationMigration(store).planIsolation(shared.id, isolate = true)
        assertEquals(listOf("options.txt"), one.entries.map { it.relativePath })
    }

    @Test
    fun `plan never moves files out of the shared game directory`() {
        val store = store()
        val shared = instance(store, isolate = false)
        write(store.instancePath(shared.id).resolve("mods/a.jar"), "jar")

        val plan =
            IsolationMigration(store).planIsolation(shared.id, isolate = true, moveSource = true)

        assertEquals(MigrationAction.Copy, plan.entries.single().action)
        assertEquals(0, plan.moveCount)
        assertTrue(plan.entries.single().reason.contains("只复制"))
    }

    // ---- 执行 ----

    @Test
    fun `switching to isolation copies files and updates the record`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val shared = instance(store, isolate = false)
        val root = store.instancePath(shared.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("options.txt"), "options")

        val executed =
            migration.changeIsolation(
                shared.id,
                isolate = true,
                context = JobContext(store, "fixture"),
            )

        assertEquals(2, executed)
        val updated = store.instance(shared.id)
        assertEquals(VersionIsolation.Enabled, updated.isolation)
        val target = store.instancePath(shared.id)
        assertTrue(target.startsWith(sharedRoot(store).resolve("versions")))
        assertEquals("jar", target.resolve("mods/a.jar").readText())
        assertEquals("options", target.resolve("options.txt").readText())
        assertTrue(root.resolve("mods/a.jar").isRegularFile(), "共用游戏目录的源文件必须保留")
        assertTrue(root.resolve("options.txt").isRegularFile())
    }

    @Test
    fun `switching to shared copies by default and keeps the isolated source`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("config/a.toml"), "cfg")

        val executed =
            migration.changeIsolation(
                isolated.id,
                isolate = false,
                context = JobContext(store, "fixture"),
            )

        assertEquals(1, executed)
        assertEquals(VersionIsolation.Disabled, store.instance(isolated.id).isolation)
        assertEquals("cfg", sharedRoot(store).resolve("config/a.toml").readText())
        assertTrue(root.resolve("config/a.toml").isRegularFile(), "默认不删除源文件")
        assertTrue(root.isDirectory())
    }

    @Test
    fun `moveSource removes the isolated source after a successful switch`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("config/a.toml"), "cfg")

        val executed =
            migration.changeIsolation(
                isolated.id,
                isolate = false,
                moveSource = true,
                context = JobContext(store, "fixture"),
            )

        assertEquals(1, executed)
        assertEquals("cfg", sharedRoot(store).resolve("config/a.toml").readText())
        assertFalse(root.resolve("config/a.toml").exists())
        assertEquals(VersionIsolation.Disabled, store.instance(isolated.id).isolation)
    }

    @Test
    fun `cancelled migration restores the source and the target`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        val payload = ByteArray(128 * 1024) { it.toByte() }
        repeat(240) { index ->
            val file = root.resolve("mods/f%03d.jar".format(index))
            file.parent.createDirectories()
            file.writeBytes(payload)
        }
        val targetMods = sharedRoot(store).resolve("mods")
        val job = Job()
        val cancelled = AtomicBoolean(false)
        val watcher = Thread {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val started = runCatching {
                    targetMods.isDirectory() && targetMods.listDirectoryEntries().isNotEmpty()
                }
                    .getOrDefault(false)
                if (started) {
                    job.cancel()
                    cancelled.set(true)
                    return@Thread
                }
                Thread.yield()
            }
        }
            .apply {
                isDaemon = true
                start()
            }
        try {
            assertFailsWith<CancellationException> {
                withContext(job) {
                    migration.changeIsolation(
                        isolated.id,
                        isolate = false,
                        context = JobContext(store, "fixture"),
                    )
                }
            }
        } finally {
            watcher.join(5_000)
        }
        assertTrue(cancelled.get(), "watcher did not cancel the migration")
        assertEquals(240, root.resolve("mods").listDirectoryEntries().size)
        assertFalse(targetMods.exists(), "回滚后目标目录不应残留文件")
        assertEquals(VersionIsolation.Enabled, store.instance(isolated.id).isolation)
    }

    @Test
    fun `migration rolls back files when the record update fails`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("config/a.toml"), "cfg")
        val plan = migration.planIsolation(isolated.id, isolate = false)
        val backup = temporary.resolve("backup/record-failure")
        val transaction = IsolationMigrationTransaction(store, plan.sourceRoot, backup)

        val error =
            assertFailsWith<IOException> {
                transaction.apply(
                    plan,
                    context = JobContext(store, "fixture"),
                    copySaves = true,
                    updateInstance = { throw IOException("模拟记录写入失败") },
                )
            }

        assertEquals("模拟记录写入失败", error.message)
        assertEquals("jar", root.resolve("mods/a.jar").readText())
        assertEquals("cfg", root.resolve("config/a.toml").readText())
        assertFalse(sharedRoot(store).resolve("mods").exists())
        assertFalse(sharedRoot(store).resolve("config").exists())
        assertEquals(VersionIsolation.Enabled, store.instance(isolated.id).isolation)
        assertFalse(backup.exists(), "完整回滚后不留迁移清单")
    }

    @Test
    fun `rollback failure keeps the backup directory and reports its path`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("mods/a.jar"), "jar")
        val plan = migration.planIsolation(isolated.id, isolate = false, moveSource = true)
        val sourceFile = root.resolve("mods/a.jar")
        val backup = temporary.resolve("backup/stuck")
        val transaction = IsolationMigrationTransaction(store, plan.sourceRoot, backup)

        val error =
            assertFailsWith<IOException> {
                transaction.apply(
                    plan,
                    context = JobContext(store, "fixture"),
                    copySaves = true,
                    moveSource = true,
                    updateInstance = {
                        // 用非空目录占住源路径，回滚时无法把已移动的文件搬回去。
                        sourceFile.createDirectories()
                        sourceFile.resolve("block.txt").writeText("block")
                        throw IOException("模拟记录写入失败")
                    },
                )
            }

        assertTrue(
            error.message.orEmpty().contains(backup.toAbsolutePath().normalize().toString()),
            "错误消息必须给出迁移清单的绝对路径：${error.message}",
        )
        assertTrue(backup.isDirectory())
        assertTrue(backup.resolve("isolation-plan.txt").isRegularFile())
        assertTrue(sharedRoot(store).resolve("mods/a.jar").isRegularFile(), "未回滚的文件仍在目标目录")
        assertEquals(VersionIsolation.Enabled, store.instance(isolated.id).isolation)
    }

    // ---- 跨游戏目录迁移 ----

    @Test
    fun `move relocates the whole instance into another game directory`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("saves/world/level.dat"), "level")
        write(root.resolve("logs/latest.log"), "log")
        val destination = temporary.resolve("other-game").apply { createDirectories() }

        val plan = migration.planInstanceMove(isolated.id, destination)
        assertTrue(plan.blockers.isEmpty(), plan.blockers.toString())
        assertEquals(3, plan.transferCount)

        val moved = migration.moveInstance(isolated.id, destination, JobContext(store, "fixture"))

        val expected = destination.resolve("versions/${isolated.gameDirectoryName}")
        assertEquals(expected.toAbsolutePath().normalize(), moved)
        assertEquals("jar", expected.resolve("mods/a.jar").readText())
        assertEquals("level", expected.resolve("saves/world/level.dat").readText())
        assertEquals("log", expected.resolve("logs/latest.log").readText())
        assertFalse(root.exists(), "移动成功后源目录应删除")
        val updated = store.instance(isolated.id)
        assertNotNull(updated.gameDirectoryId)
        assertTrue(updated.managedGameDirectory)
        assertEquals(
            expected.toAbsolutePath().normalize(),
            store.instancePath(isolated.id).toAbsolutePath().normalize(),
        )
        assertTrue(
            store.data.gameDirectories.any {
                Path.of(it.path).toAbsolutePath().normalize() ==
                    destination.toAbsolutePath().normalize()
            }
        )
    }

    @Test
    fun `move refuses a destination that already holds the instance folder`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        write(store.instancePath(isolated.id).resolve("mods/a.jar"), "jar")
        val destination = temporary.resolve("other-game")
        destination.resolve("versions/${isolated.gameDirectoryName}/mods").createDirectories()

        val plan = migration.planInstanceMove(isolated.id, destination)
        assertEquals(1, plan.blockers.size)
        assertTrue(plan.blockers.single().contains("同名"))

        val error =
            assertFailsWith<IllegalArgumentException> {
                migration.moveInstance(isolated.id, destination, JobContext(store, "fixture"))
            }
        assertTrue(error.message.orEmpty().contains("同名"))
        assertTrue(store.instancePath(isolated.id).resolve("mods/a.jar").isRegularFile())
    }

    @Test
    fun `move failure keeps the source and rolls back the target`() = runBlocking {
        val store = store()
        val migration = IsolationMigration(store)
        val isolated = instance(store, isolate = true)
        val root = store.instancePath(isolated.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("config/a.toml"), "cfg")
        val destination = temporary.resolve("other-game")
        val plan = migration.planInstanceMove(isolated.id, destination)
        val target = plan.targetRoot
        val backup = temporary.resolve("backup/move-failure")
        val transaction = IsolationMigrationTransaction(store, plan.sourceRoot, backup)

        assertFailsWith<IOException> {
            transaction.apply(
                plan,
                context = JobContext(store, "fixture"),
                copySaves = true,
                updateInstance = { throw IOException("模拟目录记录写入失败") },
            )
        }

        assertEquals("jar", root.resolve("mods/a.jar").readText())
        assertEquals("cfg", root.resolve("config/a.toml").readText())
        assertFalse(target.exists(), "失败后目标目录必须回滚")
        assertNull(store.instance(isolated.id).gameDirectoryId)
        assertFalse(backup.exists())
    }

    @Test
    fun `move rejects shared instances without an isolated directory`() {
        val store = store()
        val shared = instance(store, isolate = false)
        val destination = temporary.resolve("other-game")

        val error =
            assertFailsWith<IllegalArgumentException> {
                IsolationMigration(store).planInstanceMove(shared.id, destination)
            }

        assertTrue(error.message.orEmpty().contains("版本隔离"))
    }
}
