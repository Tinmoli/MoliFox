// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证以 versions/ 为唯一事实来源的实例扫描、目录变更与旧配置迁移。

package dev.molifox.core

import com.google.gson.JsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceCatalogTest {
    @TempDir lateinit var temporary: Path

    private fun version(
        root: Path,
        name: String,
        body: String? = null,
        jar: Boolean = true,
    ): Path {
        val folder = root.resolve("versions/$name").apply { createDirectories() }
        folder.resolve("$name.json").writeText(
            body
                ?: gson.toJson(
                    mapOf(
                        "id" to name,
                        "type" to "release",
                        "time" to "2026-10-29T10:00:00Z",
                        "mainClass" to "fixture.Main",
                        "libraries" to emptyList<Any>(),
                    ),
                ),
        )
        if (jar) folder.resolve("$name.jar").writeBytes(byteArrayOf())
        return folder
    }

    @Test
    fun `a directory added after start appears on the next rescan`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        assertTrue(store.data.instances.isEmpty())
        version(store.minecraftRoot, "added")
        assertTrue(store.refreshInstancesIfChanged())
        val instance = store.instance("added")
        assertEquals("added", instance.name)
        assertEquals("added", instance.gameVersion)
        assertFalse(instance.broken)
        assertTrue(instance.installed)
    }

    @Test
    fun `manual jar removal updates installed state without hiding the instance`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val folder = version(store.minecraftRoot, "manual")
        store.refreshInstances()
        assertTrue(store.instance("manual").installed)
        folder.resolve("manual.jar").toFile().delete()
        assertTrue(store.refreshInstancesIfChanged())
        val instance = store.instance("manual")
        assertFalse(instance.installed)
        assertFalse(instance.broken)
    }

    @Test
    fun `a directory removed on disk disappears on the next rescan`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val folder = version(store.minecraftRoot, "removed")
        store.refreshInstances()
        assertTrue(store.data.instances.any { it.name == "removed" })
        folder.toFile().deleteRecursively()
        assertTrue(store.refreshInstancesIfChanged())
        assertTrue(store.data.instances.none { it.name == "removed" })
        assertFailsWith<IllegalStateException> { store.instance("removed") }
    }

    @Test
    fun `invalid json is surfaced as unrecognised and is not launchable`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        version(store.minecraftRoot, "broken", body = "{not json", jar = false)
        store.refreshInstances()
        val instance = store.instance("broken")
        assertTrue(instance.broken)
        assertFalse(instance.installed)
        assertContains(instance.brokenReason, "无法解析")
        assertEquals(1, store.data.instances.size)
    }

    @Test
    fun `a pending install target is not shown as unrecognised before its json exists`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val folder = store.minecraftRoot.resolve("versions/constructing").createDirectories()
        store.beginPendingInstall("constructing", "1.20.1", "fabric-0.16")
        store.refreshInstances()
        assertTrue(store.data.instances.none { it.name == "constructing" })
        writeJson(
            folder.resolve("constructing.json"),
            mapOf(
                "id" to "constructing",
                "type" to "release",
                "gameVersion" to "1.20.1",
                "mainClass" to "fixture.Main",
                "molifoxManaged" to true,
                "libraries" to emptyList<Any>(),
            ),
        )
        folder.resolve("constructing.jar").writeBytes(byteArrayOf())
        store.endPendingInstall("constructing")
        store.refreshInstances()
        val instance = store.instance("constructing")
        assertFalse(instance.broken)
        assertEquals(store.instanceIdFor("constructing", null), instance.id)
        assertFalse(instance.externalProfile)
    }

    @Test
    fun `a broken instance cannot enter the launch job`() {
        LauncherController(dataDirectory = temporary.resolve("launch")).use { controller ->
            version(controller.store.minecraftRoot, "broken", body = "{not json", jar = false)
            controller.store.refreshInstances()
            val broken = controller.store.instance("broken")
            val failure = assertFailsWith<IllegalArgumentException> { controller.launch(broken.id) }
            assertContains(failure.message.orEmpty(), "无法识别")
        }
    }

    @Test
    fun `installer managed marker decides external profile ownership`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        version(store.minecraftRoot, "ours", body = null)
        val folder = store.minecraftRoot.resolve("versions/ours")
        writeJson(
            folder.resolve("ours.json"),
            mapOf(
                "id" to "ours",
                "type" to "release",
                "gameVersion" to "1.20.1",
                "mainClass" to "fixture.Main",
                "molifoxManaged" to true,
                "libraries" to emptyList<Any>(),
            ),
        )
        version(store.minecraftRoot, "foreign", body = null)
        store.refreshInstances()
        val ours = store.instance("ours")
        assertFalse(ours.externalProfile)
        assertTrue(ours.managedGameDirectory)
        assertEquals("1.20.1", ours.gameVersion)
        assertTrue(store.instance("foreign").externalProfile)
    }

    @Test
    fun `per instance settings follow a directory rename`() {
        LauncherController(dataDirectory = temporary.resolve("profile")).use { controller ->
            val store = controller.store
            val folder = version(store.minecraftRoot, "old-name")
            store.refreshInstances()
            val before = store.instance("old-name")
            store.updateInstance(before.id) {
                it.copy(
                    isolation = VersionIsolation.Enabled,
                    javaPath = "C:/Java/bin/java.exe",
                    iconId = "fixture-icon",
                    favorite = true,
                    lastPlayed = "2026-10-29T12:00:00Z",
                )
            }
            val renamed = controller.renameInstance(before.id, "new-name")
            assertEquals("new-name", renamed.name)
            assertFalse(folder.exists())
            assertTrue(
                store.minecraftRoot.resolve("versions/new-name/new-name.json").isRegularFile(),
            )
            val settings = requireNotNull(store.instanceSettings("new-name"))
            assertEquals(VersionIsolation.Enabled, settings.isolation)
            assertEquals("C:/Java/bin/java.exe", settings.javaPath)
            assertEquals("fixture-icon", settings.iconId)
            assertTrue(settings.favorite)
            assertEquals("2026-10-29T12:00:00Z", settings.lastPlayed)
            assertNull(store.instanceSettings("old-name"))
        }
    }

    @Test
    fun `settings for a missing folder stay inert and are pruned on the next save`() {
        val dataDirectory = temporary.resolve("profile")
        val root = temporary.resolve("games").apply { createDirectories() }
        val data =
            LauncherData(
                settings = LauncherSettings(gameRoot = root.toString()),
                instanceSettings =
                    mapOf("ghost" to InstanceSettings(isolation = VersionIsolation.Enabled)),
            )
        dataDirectory.resolve("config").createDirectories()
        writeJson(dataDirectory.resolve("config/launcher.json"), data)
        val store = LauncherStore(DesktopPlatform(), dataDirectory)
        assertTrue(store.data.instances.isEmpty())
        // 不存在的键不会被当成实例，但也不会在启动时被偷偷删掉。
        assertEquals(VersionIsolation.Enabled, store.instanceSettings("ghost")?.isolation)
        store.mutate { it.copy(settings = it.settings.copy(themeMode = "dark")) }
        val saved = readObject(dataDirectory.resolve("config/launcher.json"))
        assertTrue(saved.getAsJsonObject("instanceSettings").isEmpty)
    }

    @Test
    fun `legacy instance list migrates settings then leaves only the name keyed map`() {
        val root = temporary.resolve("games").apply { createDirectories() }
        val dataDirectory = writeLegacyInstanceJson(root)

        val store = LauncherStore(DesktopPlatform(), dataDirectory)
        val settings = requireNotNull(store.instanceSettings("legacy"))
        assertEquals(VersionIsolation.Enabled, settings.isolation)
        assertEquals("C:/Java/bin/java.exe", settings.javaPath)
        assertEquals("legacy-icon", settings.iconId)
        assertTrue(settings.favorite)
        assertEquals("2026-10-29T12:00:00Z", settings.lastPlayed)
        // 显示名也保留，列表内容来自扫描而不是旧记录。
        assertEquals("旧版", store.instance("legacy").name)
        assertTrue(
            store.state.value.logs.any {
                it.message.contains("实例列表迁移") && it.message.contains("保留 1 个实例设置")
            },
        )
        assertMigrationPersisted(dataDirectory)
    }

    @Test
    fun `a forge profile whose parent version is missing is still recognised`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        // 只保留 Forge 补丁目录，基础版本 1.20.1 不在本目录：父版本缺失不能推翻本版本的识别。
        version(
            store.minecraftRoot,
            "1.20.1-Forge_47.2.0",
            body =
                gson.toJson(
                    mapOf(
                        "id" to "1.20.1-Forge_47.2.0",
                        "inheritsFrom" to "1.20.1",
                        "type" to "release",
                        "mainClass" to "cpw.mods.bootstraplauncher.BootstrapLauncher",
                        "libraries" to
                            listOf(
                                mapOf("name" to "net.minecraftforge:forge:1.20.1-47.2.0"),
                            ),
                    ),
                ),
            jar = false,
        )
        store.refreshInstances()
        val instance = store.instance("1.20.1-Forge_47.2.0")
        assertFalse(instance.broken)
        assertEquals("1.20.1", instance.gameVersion)
        assertEquals(LoaderType.Forge, instance.loader)
        assertEquals("47.2.0", instance.loaderVersion)
    }

    @Test
    fun `version json named after its id instead of the folder is still recognised`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val folder = store.minecraftRoot.resolve("versions/1.21.4").createDirectories()
        writeJson(
            folder.resolve("vanilla-1.21.4.json"),
            mapOf(
                "id" to "vanilla-1.21.4",
                "type" to "release",
                "mainClass" to "net.minecraft.client.main.Main",
                "libraries" to emptyList<Any>(),
            ),
        )
        store.refreshInstances()
        val instance = store.instance("1.21.4")
        assertFalse(instance.broken)
        assertEquals("1.21.4", instance.gameVersion)
        assertEquals(LoaderType.Vanilla, instance.loader)
    }

    @Test
    fun `a patch profile without mainClass keeps its own loader information`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        version(
            store.minecraftRoot,
            "Fabric 0.18.4",
            body =
                gson.toJson(
                    mapOf(
                        "id" to "Fabric 0.18.4",
                        "inheritsFrom" to "1.21.11",
                        "libraries" to
                            listOf(
                                mapOf("name" to "net.fabricmc:fabric-loader:0.18.4"),
                            ),
                    ),
                ),
            jar = false,
        )
        store.refreshInstances()
        val instance = store.instance("Fabric 0.18.4")
        assertFalse(instance.broken)
        assertEquals("1.21.11", instance.gameVersion)
        assertEquals(LoaderType.Fabric, instance.loader)
        assertEquals("0.18.4", instance.loaderVersion)
    }

    /** 迁移后的文件不再含 instances，只保留按名字索引的设置映射。 */
    private fun assertMigrationPersisted(dataDirectory: Path) {
        val saved = readObject(dataDirectory.resolve("config/launcher.json"))
        assertFalse(saved.has("instances"))
        assertTrue(saved.getAsJsonObject("instanceSettings").has("legacy"))
        val reopened = LauncherStore(DesktopPlatform(), dataDirectory)
        assertEquals(
            "旧版",
            reopened.instance(requireNotNull(reopened.data.selectedInstanceId)).name,
        )
    }

    /** 写入 schemaVersion 2 的旧版 launcher.json，返回数据目录。 */
    private fun writeLegacyInstanceJson(root: Path): Path {
        val dataDirectory = temporary.resolve("profile")
        version(root, "legacy")
        val legacy = legacyInstanceFixture()
        val base = LauncherData(settings = LauncherSettings(gameRoot = root.toString()))
        val json = gson.toJsonTree(base).asJsonObject
        json.addProperty("schemaVersion", 2)
        json.add("instances", JsonArray().apply { add(gson.toJsonTree(legacy)) })
        json.getAsJsonObject("settings").addProperty("gameRoot", root.toString())
        dataDirectory.resolve("config").createDirectories()
        writeJson(dataDirectory.resolve("config/launcher.json"), json)
        return dataDirectory
    }

    private fun legacyInstanceFixture(): GameInstance =
        GameInstance(
            id = UUID.randomUUID().toString(),
            name = "旧版",
            gameVersion = "legacy",
            versionId = "legacy",
            gameDirectoryName = "legacy",
            isolation = VersionIsolation.Enabled,
            javaPath = "C:/Java/bin/java.exe",
            iconId = "legacy-icon",
            favorite = true,
            lastPlayed = "2026-10-29T12:00:00Z",
            installed = true,
        )
}
