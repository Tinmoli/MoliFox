// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证隔离切换前的移动计划：白名单、冲突、同内容跳过、存档开关与移动限制。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IsolationPlanTest {
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
    fun `plan reports conflicting targets and refuses to overwrite`() =
        runBlocking {
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
        assertTrue(
            plan.entries
                .single()
                .reason
                .contains("只复制"),
        )
    }
}
