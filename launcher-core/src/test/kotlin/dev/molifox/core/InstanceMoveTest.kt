// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例跨游戏目录移动的落点、冲突拒绝与失败回滚。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceMoveTest {
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

    @Test
    fun `move relocates the whole instance into another game directory`() =
        runBlocking {
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

            val moved =
                migration.moveInstance(
                    isolated.id,
                    destination,
                    JobContext(store, "fixture"),
                )

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
                },
            )
        }

    @Test
    fun `move refuses a destination that already holds the instance folder`() =
        runBlocking {
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
    fun `move failure keeps the source and rolls back the target`() =
        runBlocking {
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
