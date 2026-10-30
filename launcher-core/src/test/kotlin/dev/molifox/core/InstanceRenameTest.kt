// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证实例重命名：真实目录移动、版本配置同步、冲突拒绝与失败回滚。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstanceRenameTest {
    @TempDir lateinit var temporary: Path

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("MoliFox"))

    private fun version(
        root: Path,
        name: String,
        json: String? = null,
    ) {
        val folder = root.resolve("versions/$name").apply { createDirectories() }
        if (json != null) folder.resolve("$name.json").writeText(json)
    }

    private fun instance(
        store: LauncherStore,
        name: String = "旧名",
        versionId: String = "1.20.1",
        gameDirectoryName: String = name,
        externalProfile: Boolean = false,
    ): GameInstance {
        val instance =
            GameInstance(
                name = name,
                gameVersion = "1.20.1",
                versionId = versionId,
                gameDirectoryName = gameDirectoryName,
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                externalProfile = externalProfile,
                managedGameDirectory = true,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        return store.instance(instance.id)
    }

    private val ownProfile =
        """{"id":"旧名","inheritsFrom":"1.20.1","jar":"旧名","mainClass":"fixture.Main","libraries":[]}"""

    @Test
    fun `rename moves the version directory and rewrites id self jar and references`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        version(root, "1.20.1", """{"id":"1.20.1"}""")
        version(root, "其它包", """{"id":"其它包","inheritsFrom":"旧名"}""")
        val instance = instance(store)
        val folder = store.instancePath(instance.id).apply { createDirectories() }
        folder.resolve("旧名.json").writeText(ownProfile)
        folder.resolve("旧名.jar").writeBytes(byteArrayOf(1, 2, 3))
        folder.resolve("旧名-natives").createDirectories()
        folder.resolve("saves/world/level.dat").parent.createDirectories()
        folder.resolve("saves/world/level.dat").writeText("world")
        // 用户文件里出现旧名时不做任何替换。
        folder.resolve("mods/fixture.json").parent.createDirectories()
        folder.resolve("mods/fixture.json").writeText("""{"note":"旧名"}""")

        val renamed = InstanceRename(store).rename(instance.id, " 新名 ")

        assertEquals("新名", renamed.name)
        assertEquals("新名", renamed.gameDirectoryName)
        assertEquals("1.20.1", renamed.versionId)
        assertEquals(instance.id, renamed.id)
        assertFalse(folder.exists())
        val target = root.resolve("versions/新名")
        assertTrue(target.isDirectory())
        assertTrue(target.resolve("新名.jar").isRegularFile())
        assertTrue(target.resolve("新名-natives").isDirectory())
        val json = readObject(target.resolve("新名.json"))
        assertEquals("新名", json.string("id"))
        assertEquals("新名", json.string("jar"))
        assertEquals("1.20.1", json.string("inheritsFrom"))
        // 其它版本配置里的真实引用同步到新名，基础版本目录保持不动。
        assertEquals("新名", readObject(root.resolve("versions/其它包/其它包.json")).string("inheritsFrom"))
        assertEquals("1.20.1", readObject(root.resolve("versions/1.20.1/1.20.1.json")).string("id"))
        // 实例内容随目录一起移动，存档没有丢失。
        assertEquals("world", target.resolve("saves/world/level.dat").readText())
        assertEquals(
            """{"note":"旧名"}""",
            target.resolve("mods/fixture.json").readText(),
        )
    }

    @Test
    fun `not yet installed instance moves its empty directory`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        val instance = instance(store)
        store.updateInstance(instance.id) { it.copy(installed = false) }
        store.instancePath(instance.id).createDirectories()

        val renamed = InstanceRename(store).rename(instance.id, "新名")

        assertEquals("新名", renamed.gameDirectoryName)
        assertTrue(root.resolve("versions/新名").isDirectory())
        assertFalse(root.resolve("versions/旧名").exists())
    }

    @Test
    fun `rename refuses an existing directory or another instance name without touching disk`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        val instance = instance(store)
        val folder = store.instancePath(instance.id).apply { createDirectories() }
        folder.resolve("旧名.json").writeText(ownProfile)
        root.resolve("versions/占用").createDirectories()
        val rename = InstanceRename(store)

        val exists = assertFailsWith<IllegalArgumentException> { rename.plan(instance.id, "占用") }
        assertTrue(exists.message.orEmpty().contains("已存在"))

        instance(store, name = "别人", gameDirectoryName = "别人")
        val clash = assertFailsWith<IllegalArgumentException> { rename.plan(instance.id, "别人") }
        assertTrue(clash.message.orEmpty().contains("已有实例"))

        assertEquals("旧名", store.instance(instance.id).name)
        assertTrue(folder.isDirectory())
        assertTrue(folder.resolve("旧名.json").isRegularFile())
    }

    @Test
    fun `illegal names are rejected before moving anything`() {
        val store = store()
        store.minecraftRoot.createDirectories()
        val instance = instance(store)
        store.instancePath(instance.id).createDirectories()
        val rename = InstanceRename(store)
        for (name in listOf("", "a/b", "a\\b", "a:b", "con", "x.")) {
            assertFailsWith<IllegalArgumentException>("名称 $name 应被拒绝") {
                rename.plan(instance.id, name)
            }
        }
        assertTrue(store.instancePath(instance.id).isDirectory())
        // 首尾空格在改名时会被去掉，不算非法字符。
        assertEquals("新名", rename.plan(instance.id, " 新名 ").newName)
    }

    @Test
    fun `shared base version directory cannot be renamed from under another instance`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        version(root, "1.20.1", """{"id":"1.20.1"}""")
        val base =
            instance(
                store,
                name = "1.20.1",
                versionId = "1.20.1",
                gameDirectoryName = "",
                externalProfile = true,
            )
        instance(store, name = "加载器", versionId = "1.20.1", gameDirectoryName = "加载器")

        val failure =
            assertFailsWith<IllegalArgumentException> {
                InstanceRename(store).plan(base.id, "原版")
            }
        assertTrue(failure.message.orEmpty().contains("基础版本"))
        assertTrue(root.resolve("versions/1.20.1").isDirectory())
    }

    @Test
    fun `broken version json rolls the directory and record back`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        val instance = instance(store)
        val folder = store.instancePath(instance.id).apply { createDirectories() }
        folder.resolve("旧名.json").writeText("{ not json")
        folder.resolve("旧名.jar").writeBytes(byteArrayOf(9))

        val failure =
            assertFailsWith<IOException> { InstanceRename(store).rename(instance.id, "新名") }

        assertTrue(failure.message.orEmpty().contains("已回滚"))
        assertTrue(folder.resolve("旧名.json").isRegularFile())
        assertEquals("{ not json", folder.resolve("旧名.json").readText())
        assertTrue(folder.resolve("旧名.jar").isRegularFile())
        assertFalse(root.resolve("versions/新名").exists())
        assertEquals("旧名", store.instance(instance.id).name)
        assertEquals("旧名", store.instance(instance.id).gameDirectoryName)
    }

    @Test
    fun `external profile rename updates the version id`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        version(root, "PackX", """{"id":"PackX","mainClass":"fixture.Main"}""")
        val instance =
            instance(
                store,
                name = "PackX",
                versionId = "PackX",
                gameDirectoryName = "",
                externalProfile = true,
            )

        val renamed = InstanceRename(store).rename(instance.id, "PackY")

        assertEquals("PackY", renamed.name)
        assertEquals("PackY", renamed.versionId)
        assertEquals("", renamed.gameDirectoryName)
        assertTrue(root.resolve("versions/PackY/PackY.json").isRegularFile())
        assertFalse(root.resolve("versions/PackX").exists())
        assertEquals("PackY", readObject(root.resolve("versions/PackY/PackY.json")).string("id"))
    }

    @Test
    fun `component backup manifest paths follow the renamed directory`() {
        val store = store()
        val root = store.minecraftRoot.apply { createDirectories() }
        val instance = instance(store)
        val folder = store.instancePath(instance.id).apply { createDirectories() }
        folder.resolve("旧名.json").writeText(ownProfile)
        val backup =
            store.directories.instanceBackups.resolve("components/fixture").apply {
                createDirectories()
            }
        writeJson(
            backup.resolve("manifest.json"),
            mapOf(
                "instance" to instance,
                "root" to taskPathKey(root),
                "profiles" to
                    listOf(
                        mapOf(
                            "path" to "versions/旧名/旧名.json",
                            "file" to "0.json",
                            "sha256" to "0",
                        ),
                    ),
            ),
        )

        InstanceRename(store).rename(instance.id, "新名")

        val manifest = readObject(backup.resolve("manifest.json"))
        assertEquals("新名", manifest.getAsJsonObject("instance").string("name"))
        assertEquals(
            "versions/新名/新名.json",
            manifest
                .getAsJsonArray("profiles")
                .single()
                .asJsonObject
                .string("path"),
        )
    }
}
