// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证版本隔离默认开启、显式选择持久化，以及外部实例的目录证据推断。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsolationInferenceTest {
    @TempDir lateinit var temporary: Path

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))

    private fun version(
        root: Path,
        id: String,
    ): Path {
        val folder = root.resolve("versions/$id").apply { createDirectories() }
        writeJson(
            folder.resolve("$id.json"),
            mapOf(
                "id" to id,
                "mainClass" to "FixtureMinecraft",
                "libraries" to emptyList<String>(),
                "minecraftArguments" to "--username ${'$'}{auth_player_name}",
            ),
        )
        return folder
    }

    private fun scannedStore(
        root: Path,
        isolateVersions: Boolean = true,
    ): LauncherStore {
        val store = store()
        store.mutate {
            it.copy(
                settings =
                    it.settings.copy(gameRoot = root.toString(), isolateVersions = isolateVersions),
            )
        }
        store.refreshInstances()
        return store
    }

    @Test
    fun `fresh and never-chosen configs default to isolated`() {
        val store = store()
        assertTrue(store.data.settings.isolateVersions)
        // 旧配置里没有这个键 = 用户从未选择：按默认开启读入。
        val configuration = store.directories.configuration
        val json = readObject(configuration)
        json.getAsJsonObject("settings").remove("isolateVersions")
        writeJson(configuration, json)
        val reloaded = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        assertTrue(reloaded.data.settings.isolateVersions)
    }

    @Test
    fun `explicitly saved off survives reload`() {
        store().mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        assertFalse(
            LauncherStore(
                DesktopPlatform(),
                temporary.resolve("MoliFox"),
            ).data.settings.isolateVersions,
        )
    }

    @Test
    fun `game language follows launcher when the config never stored the switch`() {
        val store = store()
        assertTrue(store.data.settings.gameLanguageFollowsLauncher)
        // 旧配置里没有这个键 = 用户从未选择：Gson 会把缺失的布尔字段填成 false，必须按默认开启读入。
        val configuration = store.directories.configuration
        val json = readObject(configuration)
        json.getAsJsonObject("settings").remove("gameLanguageFollowsLauncher")
        writeJson(configuration, json)
        val reloaded = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))
        assertTrue(reloaded.data.settings.gameLanguageFollowsLauncher)
    }

    @Test
    fun `explicitly disabled game language following survives reload`() {
        store().mutate { it.copy(settings = it.settings.copy(gameLanguageFollowsLauncher = false)) }
        assertFalse(
            LauncherStore(
                DesktopPlatform(),
                temporary.resolve("MoliFox"),
            ).data.settings.gameLanguageFollowsLauncher,
        )
    }

    @Test
    fun `version directory with saves is inferred isolated even when the global default is off`() {
        val root = temporary.resolve("game")
        val folder = version(root, "独立隔离版本")
        folder.resolve("saves").createDirectories()
        val store = scannedStore(root, isolateVersions = false)
        val instance = store.data.instances.single()
        assertEquals(VersionIsolation.Inherit, instance.isolation)
        assertEquals(IsolationSource.Inferred, store.isolationSource(instance))
        assertEquals("saves", store.isolationEvidence(instance))
        assertTrue(store.isolated(instance))
        assertEquals(folder.toAbsolutePath(), store.gamePath(instance))
        assertTrue(
            store.state.value.logs.any {
                it.message == "推断版本隔离：独立隔离版本（目录内有 saves）"
            },
        )
    }

    @Test
    fun `version without game data follows the global setting`() {
        val root = temporary.resolve("game")
        version(root, "纯净版本")
        val store = scannedStore(root)
        var instance = store.data.instances.single()
        assertEquals(IsolationSource.GlobalDefault, store.isolationSource(instance))
        assertTrue(store.isolated(instance))
        assertEquals(root.resolve("versions/纯净版本").toAbsolutePath(), store.gamePath(instance))
        store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
        instance = store.data.instances.single()
        assertFalse(store.isolated(instance))
        assertEquals(root.toAbsolutePath(), store.gamePath(instance))
    }

    @Test
    fun `explicit per instance choice beats inference`() {
        val root = temporary.resolve("game")
        version(root, "独立隔离版本").resolve("mods").createDirectories()
        val store = scannedStore(root)
        val instance = store.data.instances.single()
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Disabled) }
        val shared = store.instance(instance.id)
        assertEquals(IsolationSource.Explicit, store.isolationSource(shared))
        assertNull(store.isolationEvidence(shared))
        assertEquals(root.toAbsolutePath(), store.gamePath(shared))
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Enabled) }
        val isolated = store.instance(instance.id)
        assertEquals(VersionIsolation.Enabled, isolated.isolation)
        assertEquals(root.resolve("versions/独立隔离版本").toAbsolutePath(), store.gamePath(isolated))
    }

    @Test
    fun `ui facing title reports inference instead of pretending a user choice`() {
        val root = temporary.resolve("game")
        version(root, "独立隔离版本").resolve("options.txt").writeText("lang:zh_cn\n")
        val store = scannedStore(root)
        val instance = store.data.instances.single()
        assertEquals("版本隔离（自动推断）", store.isolationTitle(instance))
        store.updateInstance(instance.id) { it.copy(isolation = VersionIsolation.Disabled) }
        assertEquals("共用游戏目录", store.isolationTitle(store.instance(instance.id)))
        val plainRoot = temporary.resolve("plain")
        version(plainRoot, "纯净版本")
        val plain = scannedStore(plainRoot, isolateVersions = false)
        assertEquals("共用游戏目录（全局默认）", plain.isolationTitle(plain.data.instances.single()))
    }
}
