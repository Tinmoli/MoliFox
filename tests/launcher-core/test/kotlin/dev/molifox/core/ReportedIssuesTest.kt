// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证已报告实例版本和皮肤问题的回归的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.imageio.ImageIO
import javax.tools.ToolProvider
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ReportedIssuesTest {
    @TempDir lateinit var temporary: Path

    private fun profile(
        root: Path,
        id: String,
        extras: Map<String, Any> = emptyMap(),
        embeddedVersion: String? = null,
    ) {
        val directory = root.resolve("versions/$id").apply { createDirectories() }
        writeJson(
            directory.resolve("$id.json"),
            mapOf("id" to id, "mainClass" to "Fixture", "libraries" to emptyList<String>()) +
                extras,
        )
        JarOutputStream(directory.resolve("$id.jar").outputStream()).use { jar ->
            if (embeddedVersion != null) {
                jar.putNextEntry(JarEntry("version.json"))
                jar.write(gson.toJson(mapOf("id" to embeddedVersion)).toByteArray())
                jar.closeEntry()
            }
        }
    }

    @Test
    fun `custom profiles resolve game versions from metadata rather than pack names`() {
        val root = temporary.resolve("games")
        val variants =
            mapOf(
                "TwilightMC-26.3v1.0" to mapOf("clientVersion" to "26.3"),
                "TwilightMC-26.2v1.0" to
                    mapOf("patches" to listOf(mapOf("id" to "game", "version" to "26.2"))),
                "中文改名[整合包]" to emptyMap(),
            )
        variants.forEach { (id, extras) ->
            profile(root, id, extras, if (extras.isEmpty()) "1.21.11" else null)
        }
        profile(root, "fabric-inherited", mapOf("inheritsFrom" to "TwilightMC-26.3v1.0"))
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        GameDirectoryService(store).add(root)
        val detected = store.data.instances.associate { it.versionId to it.gameVersion }
        assertEquals("26.3", detected["TwilightMC-26.3v1.0"])
        assertEquals("26.2", detected["TwilightMC-26.2v1.0"])
        assertEquals("1.21.11", detected["中文改名[整合包]"])
        assertEquals("26.3", detected["fabric-inherited"])
    }

    @Test
    fun `metadata repair preserves instance identity settings and external files`() {
        val root = temporary.resolve("game")
        profile(root, "MyPack", mapOf("clientVersion" to "26.3"))
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        val directories = GameDirectoryService(store)
        directories.add(root)
        val original =
            store.data.instances
                .single()
                .copy(gameVersion = "MyPack", memoryMb = 3072, notes = "keep me")
        store.updateInstance(original.id) { original }
        val before = root.resolve("versions/MyPack/MyPack.json").readBytes()
        directories.refreshVersionMetadata()
        assertEquals(original.copy(gameVersion = "26.3"), store.instance(original.id))
        assertContentEquals(before, root.resolve("versions/MyPack/MyPack.json").readBytes())
    }

    @Test
    fun `search quotes version facets and encodes Chinese and reserved characters once`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
            val instance = GameInstance(gameVersion = "26.3", loader = LoaderType.Fabric)
            store.mutate { it.copy(instances = listOf(instance)) }
            MockWebServer().use { server ->
                NetworkClient().use { network ->
                    server.enqueue(MockResponse().setBody("{\"hits\":[],\"total_hits\":0}"))
                    val service =
                        ContentService(
                            store,
                            network,
                            MinecraftInstaller(store, network, JavaService(store, network)),
                            server.loopbackUrl("/v2").toString(),
                        )
                    service.search("中文 Sodium & +", "mod", instance.id)
                    val request = server.takeRequest()
                    assertEquals(
                        "中文 Sodium & +",
                        checkNotNull(request.requestUrl).queryParameter("query"),
                    )
                    val facets =
                        com.google.gson.JsonParser
                            .parseString(
                                checkNotNull(request.requestUrl).queryParameter("facets"),
                            ).asJsonArray
                    assertEquals("versions:\"26.3\"", facets[1].asJsonArray[0].asString)
                    assertEquals("categories:fabric", facets[2].asJsonArray[0].asString)
                }
            }
        }

    @Test
    fun `official HTTP skin URL is upgraded without allowing alternate origins`() {
        val hash = "a".repeat(64)
        assertEquals(
            "https://textures.minecraft.net/texture/$hash",
            normalizeSkinTextureUrl("http://textures.minecraft.net/texture/$hash"),
        )
        for (
        url in
        listOf(
            "http://textures.minecraft.net.evil/texture/$hash",
            "https://user@textures.minecraft.net/texture/$hash",
            "https://textures.minecraft.net:8443/texture/$hash",
            "https://textures.minecraft.net/texture/$hash?secret=x",
            "file:///texture/$hash",
        )
        ) {
            assertFailsWith<IllegalArgumentException> { normalizeSkinTextureUrl(url) }
        }
    }

    private fun skinBytes(color: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
            for (x in 8..15) for (y in 8..15) image.setRGB(x, y, color)
            ImageIO.write(image, "png", output)
            output.toByteArray()
        }

    @Test
    fun `skin cache keys include URL refresh overwrites and corrupt files retry`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
            NetworkClient().use { network ->
                var requests = 0
                var bytes = skinBytes(0xFF112233.toInt())
                val cache =
                    AccountSkinCache(store, network) {
                        requests++
                        bytes
                    }
                val account =
                    Account(
                        type = AccountType.Microsoft,
                        uuid = "fixture",
                        skinUrl = "http://textures.minecraft.net/texture/${"a".repeat(64)}",
                    )
                val first = requireNotNull(cache.load(account, false))
                assertTrue(validSkinTexture(first))
                assertEquals(first, cache.load(account, false))
                assertEquals(1, requests)
                bytes = skinBytes(0xFF332211.toInt())
                cache.load(account, true)
                assertContentEquals(bytes, first.readBytes())
                first.writeText("bad partial download")
                assertNotNull(cache.load(account, false))
                assertTrue(validSkinTexture(first))
                assertOtherSkinGetsItsOwnKey(cache, account, first)
                assertEquals(4, requests)
            }
        }

    /** 换用另一个纹理地址后缓存键不同，且不影响已缓存的图。 */
    private suspend fun assertOtherSkinGetsItsOwnKey(
        cache: AccountSkinCache,
        account: Account,
        first: Path,
    ) {
        val other =
            requireNotNull(
                cache.load(
                    account.copy(
                        skinUrl = "https://textures.minecraft.net/texture/${"b".repeat(64)}",
                    ),
                    false,
                ),
            )
        assertNotEquals(first, other)
    }

    @Test
    fun `skin errors keep a verified cache and cancellation propagates`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
            val account =
                Account(
                    type = AccountType.Microsoft,
                    skinUrl = "http://textures.minecraft.net/texture/${"a".repeat(64)}",
                )
            NetworkClient().use { network ->
                var fail = false
                val cache =
                    AccountSkinCache(store, network) {
                        if (fail) error("offline") else skinBytes(0xFF123456.toInt())
                    }
                val original = cache.load(account, false)
                fail = true
                assertEquals(original, cache.load(account, true))
                assertFailsWith<CancellationException> {
                    AccountSkinCache(store, network) { throw CancellationException("cancel") }
                        .load(account, true)
                }
                assertTrue(
                    store.cacheDirectory
                        .resolve("skins")
                        .listDirectoryEntries("*.tmp")
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `skin cleanup failures preserve success fallback and cancellation`() = runBlocking {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("launcher"))
        val account =
            Account(
                type = AccountType.Microsoft,
                skinUrl = "https://textures.minecraft.net/texture/${"a".repeat(64)}",
            )
        NetworkClient().use { network ->
            var bytes = skinBytes(0xFF123456.toInt())
            var cancel = false
            val cache =
                AccountSkinCache(store, network, removeTemporary = {
                    if (cancel) throw CancellationException("cancel during cleanup")
                    throw IOException("fixture locked file")
                }) { bytes }
            val original = requireNotNull(cache.load(account, false))
            assertTrue(validSkinTexture(original))
            bytes = byteArrayOf(1, 2, 3)
            assertEquals(original, cache.load(account, true))
            assertTrue(validSkinTexture(original))
            cancel = true
            assertFailsWith<CancellationException> { cache.load(account, true) }
        }
    }

    @Test
    fun `automatic allocation grows with mod count and stays steppable`() {
        val withoutMods = automaticMemoryMb(16384, 8192, "x64", modCount = 0)
        val manyMods = automaticMemoryMb(16384, 8192, "x64", modCount = 60)
        assertTrue(manyMods > withoutMods, "60 个 mod 应得到更多内存：$manyMods <= $withoutMods")
        assertTrue(manyMods <= 8192)
        assertEquals(0, manyMods % 256)
        val folder = temporary.resolve("mods-fixture")
        folder.resolve("mods").createDirectories()
        listOf("a.jar", "b.zip", "c.litemod", "notes.txt").forEach {
            folder.resolve("mods/$it").writeText("x")
        }
        assertEquals(3, countInstanceMods(folder))
    }

    @Test
    fun `automatic allocation respects pressure limits architecture and manual overrides`() {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val totalMemoryMb = 16384
                override val availableMemoryMb = 8192
            }
        val settings = LauncherSettings(autoMemory = true, initialMemoryMb = 4096)
        val instance = GameInstance()
        val automatic = effectiveGameMemory(settings, instance, platform)
        assertTrue(automatic.automatic)
        assertTrue(automatic.maximumMb in 512..8192)
        assertTrue(automatic.maximumMb % 256 == 0)
        assertTrue(automatic.initialMb <= automatic.maximumMb)
        assertTrue(automaticMemoryMb(16384, 1024) < automatic.maximumMb)
        assertTrue(automaticMemoryMb(65536, 65536) <= 12288)
        assertTrue(automaticMemoryMb(16384, 8192, "x86") <= 1024)
        assertEquals(
            2048,
            effectiveGameMemory(settings, instance.copy(memoryMb = 2048), platform).maximumMb,
        )
        assertEquals(
            3072,
            effectiveGameMemory(
                settings.copy(memoryMb = 3072),
                instance.copy(autoMemory = false),
                platform,
            ).maximumMb,
        )
        assertTrue(
            effectiveGameMemory(
                settings.copy(autoMemory = false),
                instance.copy(autoMemory = true),
                platform,
            ).automatic,
        )
    }

    @Test
    fun `manual allocation and instance automatic memory preference persist`() {
        val old =
            gson.toJsonTree(LauncherSettings(memoryMb = 2048)).asJsonObject.apply {
                remove("autoMemory")
            }
        assertFalse(gson.fromJson(old, LauncherSettings::class.java).autoMemory)
        val instance = GameInstance(autoMemory = true)
        assertEquals(
            true,
            gson.fromJson(gson.toJson(instance), GameInstance::class.java).autoMemory,
        )
    }

    @Test
    fun `settings draft keeps unsaved input and independent live appearance changes`() {
        val base = LauncherSettings()
        val edited = base.copy(memoryMb = 3072, autoMemory = true)
        val appearance = base.copy(fontScale = 1.25f, backgroundOpacity = .5f, themeMode = "dark")
        val merged = mergeSettingsDraft(base, edited, appearance)
        assertEquals(3072, merged.memoryMb)
        assertTrue(merged.autoMemory)
        assertEquals(1.25f, merged.fontScale)
        assertEquals(.5f, merged.backgroundOpacity)
        assertEquals("dark", merged.themeMode)
        assertEquals(appearance, mergeSettingsDraft(base, base, appearance))
    }

    @Test
    fun `automatic memory reaches real Java child and keeps isolated working directory`() =
        runBlocking {
            val platform =
                object : PlatformServices by DesktopPlatform() {
                    override val totalMemoryMb = 2048
                    override val availableMemoryMb = 1024
                }
            val store = LauncherStore(platform, temporary.resolve("launcher"))
            val instance = memoryProbeInstance()
            store.mutate { it.copy(instances = listOf(instance)) }
            compileMemoryProbe()
            installMemoryProbeJar(store)
            val plan =
                LaunchPlanner(store)
                    .plan(
                        instance,
                        memoryProbeProfile(),
                        memoryProbeRuntime(platform.javaExecutable),
                        GameCredentials("Fixture", offlineUuid("Fixture")),
                        false,
                    )
            assertMemoryProbeOutput(plan)
        }

    private fun memoryProbeInstance(): GameInstance =
        GameInstance(
            name = "memory",
            gameVersion = "fixture",
            versionId = "fixture",
            installed = true,
            autoMemory = true,
            initialMemoryMb = 128,
        )

    /** 编译探针类：启动后打印实际最大堆与工作目录，供下面的断言核对。 */
    private fun compileMemoryProbe() {
        val source =
            temporary.resolve("MemoryProbe.java").apply {
                writeText(
                    """public class MemoryProbe {
                        public static void main(String[] args) {
                            System.out.println(Runtime.getRuntime().maxMemory()/1048576);
                            System.out.println(System.getProperty("user.dir"));
                        } }""",
                )
            }
        assertEquals(
            0,
            ToolProvider
                .getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-d",
                    temporary.toString(),
                    source.toString(),
                ),
        )
    }

    /** 把编译好的探针类打进实例的 `versions/fixture/fixture.jar`。 */
    private fun installMemoryProbeJar(store: LauncherStore) {
        val folder =
            store.minecraftRoot.resolve("versions/fixture").apply { createDirectories() }
        JarOutputStream(folder.resolve("fixture.jar").outputStream()).use { jar ->
            jar.putNextEntry(JarEntry("MemoryProbe.class"))
            jar.write(temporary.resolve("MemoryProbe.class").readBytes())
            jar.closeEntry()
        }
    }

    private fun memoryProbeProfile(): ResolvedProfile =
        ResolvedProfile(
            "fixture",
            "fixture",
            gson
                .toJsonTree(
                    mapOf("mainClass" to "MemoryProbe", "libraries" to emptyList<String>()),
                ).asJsonObject,
        )

    private fun memoryProbeRuntime(executable: String): JavaRuntime =
        JavaRuntime(
            Path.of(System.getProperty("java.home"), "bin", executable).toString(),
            Runtime.version().feature(),
            "fixture",
            "x64",
        )

    /** 真正启动子进程：参数里的堆大小与隔离工作目录必须与计划一致。 */
    private fun assertMemoryProbeOutput(plan: LaunchPlan) {
        assertContains(plan.arguments, "-Xmx512M")
        assertContains(plan.arguments, "-Xms128M")
        plan.gameDirectory.createDirectories()
        val process =
            ProcessBuilder(listOf(plan.executable) + plan.arguments)
                .directory(plan.gameDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.exitValue(), output)
            assertContains(output, "512")
            assertContains(output, plan.gameDirectory.toString())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
