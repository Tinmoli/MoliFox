// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证外部游戏目录里的加载器升级：合并原配置、保留自定义字段、备份与失败回滚。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    @Test
    fun `conditional argument objects are kept while string flags still replace`() {
        val original =
            argumentsProfile(
                """{"game": ["--username", "Steve", {"rules": [], "value": ["--demo"]}],""" +
                    """"jvm": ["-Dold=true", {"rules": [], "value": """ +
                    """["-Dcond=true"]}, ["nested"]]}""",
            )
        val loader =
            argumentsProfile(
                """{"game": [{"rules": [], "value": ["--demo"]}, "--username", "Alex"],""" +
                    """"jvm": [{"rules": [], "value": ["-Dcond=true"]}, "-Dnew=true"]}""",
            )

        val arguments = mergeExternalLoaderProfile(original, loader).obj("arguments")
        val game = arguments.array("game")
        val jvm = arguments.array("jvm")

        // 旧 --username 的紧跟值 Steve 被新值替换，对象条件参数原样保留。
        assertEquals(listOf("--username", "Alex"), stringTokens(game))
        assertEquals(1, game.count { it.isJsonObject })
        assertEquals(
            "--demo",
            game
                .first { it.isJsonObject }
                .asJsonObject
                .array("value")
                .single()
                .asString,
        )
        // 新配置里结构相同的条件参数不再追加；未在旧配置出现的开关追加到末尾。
        assertEquals(listOf("-Dold=true", "-Dnew=true"), stringTokens(jvm))
        assertEquals(1, jvm.count { it.isJsonObject })
        // 数组元素同样不是字符串 token：不崩溃、原样保留。
        assertEquals(1, jvm.count { it.isJsonArray })
    }

    @Test
    fun `conditional argument objects dedupe by structure instead of key order`() {
        val original =
            argumentsProfile(
                """{"game": [{"rules": [{"action": "allow", "features": """ +
                    """{"has_custom_resolution": true}}], "value": ["--width", "1280"]}]}""",
            )
        val loader =
            argumentsProfile(
                """{"game": [{"value": ["--width", "1280"], "rules": [{"features": """ +
                    """{"has_custom_resolution": true}, "action": "allow"}]}, """ +
                    """{"rules": [{"action": "disallow"}], "value": ["--width", "640"]}]}""",
            )

        val game = mergeExternalLoaderProfile(original, loader).obj("arguments").array("game")

        // 成员顺序不同但结构相同的对象去重；结构不同的条件参数保留。
        assertEquals(2, game.size())
        assertEquals(
            "allow",
            game
                .first()
                .asJsonObject
                .array("rules")
                .single()
                .asJsonObject
                .string("action"),
        )
        assertEquals(
            "disallow",
            game
                .last()
                .asJsonObject
                .array("rules")
                .single()
                .asJsonObject
                .string("action"),
        )
    }

    @Test
    fun `missing arguments on either side keeps the original structure`() {
        val withoutArguments =
            JsonParser.parseString("""{"id": "ForeignFabric"}""").asJsonObject
        val oldGame =
            JsonParser.parseString("""{"arguments": {"game": ["--keep"]}}""").asJsonObject
        val newGame =
            JsonParser.parseString("""{"arguments": {"game": ["--new"]}}""").asJsonObject

        val neither =
            mergeExternalLoaderProfile(withoutArguments.deepCopy(), withoutArguments.deepCopy())
        assertFalse(neither.has("arguments"))

        val keptOld = mergeExternalLoaderProfile(oldGame.deepCopy(), withoutArguments.deepCopy())
        assertEquals(listOf("--keep"), keptOld.obj("arguments").array("game").map { it.asString })

        val onlyNew = mergeExternalLoaderProfile(withoutArguments.deepCopy(), newGame.deepCopy())
        assertEquals(listOf("--new"), onlyNew.obj("arguments").array("game").map { it.asString })
    }

    @Test
    fun `legacy minecraftArguments is replaced only when the loader provides one`() {
        val original =
            JsonParser
                .parseString(
                    """{"minecraftArguments": "--tweakClass old.Tweak"}""",
                ).asJsonObject
        val loader =
            JsonParser
                .parseString(
                    """{"minecraftArguments": "--tweakClass new.Tweak"}""",
                ).asJsonObject
        val withoutLegacy =
            JsonParser.parseString("""{"mainClass": "fixture.NewKnotClient"}""").asJsonObject

        assertEquals(
            "--tweakClass new.Tweak",
            mergeExternalLoaderProfile(original.deepCopy(), loader).string("minecraftArguments"),
        )
        // 1.12 加载器没给新值时保留原文。
        assertEquals(
            "--tweakClass old.Tweak",
            mergeExternalLoaderProfile(original.deepCopy(), withoutLegacy)
                .string("minecraftArguments"),
        )
    }

    /** 只包一层 arguments 的合成版本 JSON，减少每个用例里的重复样板。 */
    private fun argumentsProfile(arguments: String): JsonObject =
        JsonParser.parseString("""{"arguments": $arguments}""").asJsonObject

    private fun stringTokens(array: JsonArray): List<String> =
        array.mapNotNull { element -> element.takeIf { it.isJsonPrimitive }?.asString }

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
