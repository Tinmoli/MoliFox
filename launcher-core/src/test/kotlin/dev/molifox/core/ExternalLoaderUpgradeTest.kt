// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证外部游戏目录里的加载器升级：合并原配置、保留自定义字段、备份与失败回滚。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalLoaderUpgradeTest : ComponentMaintenanceTestSupport() {
    @Test
    fun `external upgrade merges into the original profile keeps its fields and backs it up`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val fixture = fixture(server)
                server.dispatcher = dispatcher(fixture)
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val (external, folder) = prepareExternalInstance(fixture, store, "foreign")
                    val jsonPath = folder.resolve("ForeignFabric.json")
                    val before = Files.readAllBytes(jsonPath)
                    val mods =
                        folder.resolve("mods").createDirectories().resolve("keep.jar").apply {
                            writeText("keep")
                        }
                    val sibling = folder.resolve("foreign.txt").apply { writeText("untouched") }
                    val installer = installer(server, store, network)
                    assertNull(installer.externalLoaderUpgradeBlocker(external))
                    installer.upgradeLoader(
                        external.id,
                        newLoader,
                        JobContext(store, "external-upgrade"),
                    )
                    assertMergedForeignProfile(store, external, jsonPath)
                    // 其它文件原样保留。
                    assertEquals("keep", mods.readText())
                    assertEquals("untouched", sibling.readText())
                    assertForeignBackup(folder, before)
                }
            }
        }

    @Test
    fun `failed external upgrade restores the original json byte for byte`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
                val fixture = fixture(server)
                server.dispatcher = dispatcher(fixture)
                val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
                NetworkClient(source = { "official" }).use { network ->
                    val (external, folder) =
                        prepareExternalInstance(fixture, store, "foreign-failure")
                    val jsonPath = folder.resolve("ForeignFabric.json")
                    val before = Files.readAllBytes(jsonPath)
                    val sibling = folder.resolve("foreign.txt").apply { writeText("untouched") }
                    server.dispatcher =
                        dispatcher(fixture, newLibraryUrl = "${fixture.base}/broken.jar")
                    assertFails {
                        installer(server, store, network).upgradeLoader(
                            external.id,
                            newLoader,
                            JobContext(store, "external-upgrade-failure"),
                        )
                    }
                    assertContentEquals(before, Files.readAllBytes(jsonPath))
                    assertEquals(oldLoader, store.instance(external.id).loaderVersion)
                    assertEquals("untouched", sibling.readText())
                    // 备份保留，且与恢复后的 JSON 内容一致。
                    assertForeignBackup(folder, before)
                }
            }
        }

    @Test
    fun `external upgrade with a missing base version reports the reason and changes nothing`() {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        NetworkClient().use { network ->
            val root = temporary.resolve("foreign-missing-base").createDirectories()
            registerExternalRoot(store, root)
            val fixture = Fixture("", "", ByteArray(0), ByteArray(0), ByteArray(0), ByteArray(0))
            val folder =
                writeExternalFabricVersion(
                    root,
                    fixture,
                    oldLoader,
                    "https://example.invalid/loader.jar",
                    ByteArray(0),
                    parentPresent = false,
                )
            store.refreshInstances()
            val instance = store.instance("ForeignFabric")
            assertTrue(instance.externalProfile)
            // 父版本缺失只影响继承链，不把实例判成无法识别；升级仍应被拒绝。
            assertFalse(instance.broken)
            val jsonPath = folder.resolve("ForeignFabric.json")
            val before = Files.readAllBytes(jsonPath)
            val blocker =
                MinecraftInstaller(store, network, JavaService(store, network))
                    .externalLoaderUpgradeBlocker(instance)
            assertNotNull(blocker)
            assertContains(blocker, "基础版本 $gameVersion")
            assertContains(blocker, "不存在")
            assertContentEquals(before, Files.readAllBytes(jsonPath))
            assertTrue(
                folder.listDirectoryEntries().none {
                    it.name.contains(".molifox-backup-")
                },
            )
        }
    }

    /** 注册外部游戏根并写入旧加载器版本，返回实例与它的版本目录。 */
    private fun prepareExternalInstance(
        fixture: Fixture,
        store: LauncherStore,
        rootName: String,
    ): Pair<GameInstance, Path> {
        val root = temporary.resolve(rootName).createDirectories()
        registerExternalRoot(store, root)
        val folder =
            writeExternalFabricVersion(
                root,
                fixture,
                oldLoader,
                "${fixture.base}/old-loader.jar",
                fixture.oldLibrary,
            )
        store.refreshInstances()
        val external = store.instance("ForeignFabric")
        assertTrue(external.externalProfile)
        assertEquals(LoaderType.Fabric, external.loader)
        assertEquals(oldLoader, external.loaderVersion)
        assertTrue(external.installed)
        return external to folder
    }

    /** 原结构保留：id、inheritsFrom 与自定义字段都在，且不写自有标记/游戏版本字段。 */
    private fun assertMergedForeignProfile(
        store: LauncherStore,
        external: GameInstance,
        jsonPath: Path,
    ) {
        val json = readObject(jsonPath)
        assertEquals("ForeignFabric", json.string("id"))
        assertEquals(gameVersion, json.string("inheritsFrom"))
        assertFalse(json.has("molifoxManaged"))
        assertFalse(json.has("_minecraftVersion"))
        assertFalse(json.has("gameVersion"))
        assertEquals("fixture.KnotClient", json.string("mainClass"))
        assertEquals("yes", json.obj("foreignCustom").string("keep"))
        val names = json.array("libraries").map { it.asJsonObject.string("name") }
        assertTrue(names.none { it == "net.fabricmc:fabric-loader:$oldLoader" })
        assertEquals(
            listOf("net.fabricmc:fabric-loader:$newLoader"),
            names.filter { it.startsWith("net.fabricmc:fabric-loader:") },
        )
        assertEquals(newLoader, store.instance(external.id).loaderVersion)
    }

    /** 备份文件名带时间戳，内容与升级前的 JSON 逐字节一致。 */
    private fun assertForeignBackup(
        folder: Path,
        before: ByteArray,
    ) {
        val backups =
            folder.listDirectoryEntries().filter {
                it.name.startsWith("ForeignFabric.json.molifox-backup-")
            }
        assertEquals(1, backups.size)
        assertTrue(
            backups.single().name.matches(
                Regex("ForeignFabric\\.json\\.molifox-backup-\\d{8}-\\d{6}(-\\d+)?"),
            ),
        )
        assertContentEquals(before, Files.readAllBytes(backups.single()))
    }
}
