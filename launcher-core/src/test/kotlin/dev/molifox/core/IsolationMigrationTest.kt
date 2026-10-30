// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例隔离切换与跨游戏目录迁移的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private fun write(
        file: Path,
        text: String,
    ) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun sharedRoot(store: LauncherStore) = Path.of(store.data.settings.gameRoot)

    private fun isolatedRoot(
        store: LauncherStore,
        instance: GameInstance,
    ) = store.versionGamePath(instance)

    // ---- 执行 ----

    @Test
    fun `switching to isolation copies files and updates the record`() =
        runBlocking {
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
    fun `switching to shared copies by default and keeps the isolated source`() =
        runBlocking {
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
    fun `moveSource removes the isolated source after a successful switch`() =
        runBlocking {
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
    fun `cancelled migration restores the source and the target`() =
        runBlocking {
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
            val (watcher, cancelled) = cancelWhenTransferStarts(store, job)
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
    fun `migration rolls back files when the record update fails`() =
        runBlocking {
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
    fun `rollback failure keeps the backup directory and reports its path`() =
        runBlocking {
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

    /** 等目标目录出现文件后立即取消任务；返回监听线程与是否真的取消过。 */
    private fun cancelWhenTransferStarts(
        store: LauncherStore,
        job: Job,
    ): Pair<Thread, AtomicBoolean> {
        val targetMods = sharedRoot(store).resolve("mods")
        val cancelled = AtomicBoolean(false)
        val watcher =
            Thread {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (System.nanoTime() < deadline) {
                    val started =
                        runCatching {
                            targetMods.isDirectory() &&
                                targetMods.listDirectoryEntries().isNotEmpty()
                        }.getOrDefault(false)
                    if (started) {
                        job.cancel()
                        cancelled.set(true)
                        return@Thread
                    }
                    Thread.yield()
                }
            }.apply {
                isDaemon = true
                start()
            }
        return watcher to cancelled
    }
}
