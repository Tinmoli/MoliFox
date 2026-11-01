// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证 MultiMC/Prism 的 instance.cfg 迁移：JVM、内存、窗口、回退与明确拒绝。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiMcInstanceConfigTest {
    @TempDir lateinit var temporary: Path

    private fun info(vararg components: Pair<String, String>) =
        gson
            .toJsonTree(
                mapOf(
                    "formatVersion" to 1,
                    "components" to
                        (listOf("net.minecraft" to "1.20.1") + components).map {
                            mapOf("uid" to it.first, "version" to it.second)
                        },
                ),
            ).asJsonObject

    private fun zipBytes(entries: Map<String, String>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                for ((name, value) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    /** 可省略 instance.cfg 的归档：用来覆盖「cfg 缺失仍能导入」的回退规则。 */
    private fun archive(
        metadata: JsonObject = info(),
        prefix: String = "",
        folder: String = "minecraft/",
        config: String? = "[General]\nname=Settings\n",
        files: Map<String, String> = mapOf("config/test.txt" to "setting"),
        extras: Map<String, String> = emptyMap(),
    ): Path =
        temporary.resolve("${UUID.randomUUID()}.zip").apply {
            val entries = linkedMapOf<String, String>()
            entries["${prefix}mmc-pack.json"] = gson.toJson(metadata)
            if (config != null) entries["${prefix}instance.cfg"] = config
            entries.putAll(files.mapKeys { "$prefix$folder${it.key}" })
            entries.putAll(extras)
            writeBytes(zipBytes(entries))
        }

    private fun store() =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            },
            temporary.resolve("profile"),
        )

    private fun parse(source: Path): PackManifest =
        ZipFile(source.toFile()).use { readPackManifest(it, temporary.resolve("game"), "windows") }

    @Test
    fun `jvm memory and window settings are migrated and unmigrated keys are listed`() {
        val config =
            """
            [General]
            name=Settings
            OverrideJavaArgs=true
            JvmArgs=-Dpack.flag=1 -Dpack.value="with spaces" -Xmx8G
            OverrideMemory=true
            MaxMemAlloc=4096
            MinMemAlloc=1024
            MinecraftWinWidth=1600
            MinecraftWinHeight=900
            PreLaunchCommand=run-me
            JoinServerOnLaunch=true
            IgnoreJavaCompatibility=true
            """.trimIndent()
        val parsed = parse(archive(config = config))
        assertEquals(PackInstanceDefaults(4096, 1024, 1600, 900), parsed.instanceDefaults)
        val options = requireNotNull(parsed.launchOptions)
        assertTrue(options.overrideJvm)
        assertEquals(listOf("-Dpack.flag=1", "-Dpack.value=with spaces"), options.jvm)
        assertTrue(parsed.summary.contains("官方元数据"))
        assertTrue(parsed.summary.contains("已迁移的实例设置"))
        assertTrue(parsed.summary.contains("已忽略会覆盖内存或游戏入口的 JVM 参数"))
        assertTrue(parsed.summary.contains("启动前命令"))
        assertTrue(parsed.summary.contains("自动进入服务器"))
        assertTrue(parsed.summary.contains("Java 兼容性"))
    }

    @Test
    fun `OverrideJavaArgs decides whether pack jvm args replace or extend the global ones`() {
        val append = parse(archive(config = "[General]\nname=Append\nJvmArgs=-Dpack.only=1\n"))
        val options = requireNotNull(append.launchOptions)
        assertFalse(options.overrideJvm)
        val settings = LauncherSettings(extraJvmArgs = "-Dglobal.only=1")
        assertEquals(
            listOf("-Dglobal.only=1", "-Dpack.only=1"),
            splitArguments(requireNotNull(options.resolve(settings, "windows").jvm)),
        )
        val replace = options.copy(overrideJvm = true).resolve(settings, "windows")
        assertEquals(listOf("-Dpack.only=1"), splitArguments(requireNotNull(replace.jvm)))
    }

    @Test
    fun `out of range memory and window values are dropped with warnings`() {
        val config =
            """
            [General]
            name=Broken
            OverrideMemory=true
            MaxMemAlloc=999999
            MinMemAlloc=64
            MinecraftWinWidth=10000
            MinecraftWinHeight=100
            """.trimIndent()
        val parsed = parse(archive(config = config))
        assertNull(parsed.instanceDefaults)
        assertTrue(parsed.summary.contains("最大内存 999999 MB 超出"))
        assertTrue(parsed.summary.contains("初始内存 64 MB 超出"))
        assertTrue(parsed.summary.contains("游戏窗口宽度 10000"))
        assertTrue(parsed.summary.contains("游戏窗口高度 100"))
    }

    @Test
    fun `missing instance cfg falls back to the folder name or the archive name`() {
        val wrapped = parse(archive(config = null, prefix = "My pack/"))
        assertEquals("My pack", wrapped.request.name)
        assertTrue(wrapped.summary.contains("缺少实例名"))
        assertNull(wrapped.instanceDefaults)
        val root = archive(config = null)
        assertEquals(root.nameWithoutExtension, parse(root).request.name)
    }

    @Test
    fun `instance libraries and cleanroom are rejected while loader prefixes are stripped`() {
        ZipFile(archive(extras = mapOf("libraries/custom.jar" to "lib")).toFile()).use {
            assertFails { readPackManifest(it, temporary.resolve("game"), "windows") }
        }
        ZipFile(archive(info("net.minecraftforge" to "0.2.4")).toFile()).use {
            assertFails { readPackManifest(it, temporary.resolve("game"), "windows") }
        }
        val cases =
            mapOf(
                "net.fabricmc.fabric-loader" to ("fabric-loader-0.15.11" to "0.15.11"),
                "org.quiltmc.quilt-loader" to ("quilt-loader-0.20.0" to "0.20.0"),
                "net.minecraftforge" to ("1.20.1-forge-47.2.0" to "47.2.0"),
                "net.neoforged" to ("1.20.1-neoforge-21.1.0" to "21.1.0"),
            )
        for ((uid, versions) in cases) {
            val (packed, expected) = versions
            assertEquals(expected, parse(archive(info(uid to packed))).request.loaderVersion)
        }
    }

    @Test
    fun `import writes pack defaults to a new instance while repair keeps user values`() =
        runBlocking {
            val store = store()
            withManifestFixture(store) { service ->
                service.importPack(
                    archive(config = importedInstanceConfig()),
                    JobContext(store, "test"),
                )
                val instance = store.data.instances.single()
                assertTrue(instance.installed)
                assertPackDefaults(instance)
                assertRepairKeepsUserValues(service, store, instance)
            }
        }

    /** 本地假服务：版本清单、客户端 JAR 与资源索引都由 MockWebServer 回答。 */
    private suspend fun withManifestFixture(
        store: LauncherStore,
        action: suspend (ContentService) -> Unit,
    ) {
        MockWebServer().use { server ->
            val base = server.loopbackUrl("").toString().trimEnd('/')
            val fixture =
                CurseForgePackFixture(
                    base,
                    mutableListOf(),
                    packZipBytes(mapOf("fixture.txt" to "fixture")),
                    "resource".toByteArray(),
                )
            fixture.failDownload = false
            fixture.serve(server)
            NetworkClient().use { network ->
                val installer =
                    MinecraftInstaller(
                        store,
                        network,
                        JavaService(store, network),
                        "$base/manifest.json",
                    )
                action(ContentService(store, network, installer))
            }
        }
    }

    /** 包内声明了 JVM、内存与窗口，导入后应成为新实例的初始设置。 */
    private fun importedInstanceConfig(): String =
        """
        [General]
        name=Imported
        OverrideJavaArgs=true
        JvmArgs=-Dpack.flag=1
        OverrideMemory=true
        MaxMemAlloc=4096
        MinMemAlloc=1024
        MinecraftWinWidth=1600
        MinecraftWinHeight=900
        """.trimIndent()

    private fun assertPackDefaults(instance: GameInstance) {
        assertEquals(4096, instance.memoryMb)
        assertEquals(1024, instance.initialMemoryMb)
        assertEquals(1600, instance.width)
        assertEquals(900, instance.height)
        assertEquals(listOf("-Dpack.flag=1"), splitArguments(instance.extraJvmArgs))
    }

    /** 修复只补文件：用户改过的内存、窗口与 JVM 参数保持原样。 */
    private suspend fun assertRepairKeepsUserValues(
        service: ContentService,
        store: LauncherStore,
        instance: GameInstance,
    ) {
        store.updateInstance(instance.id) {
            it.copy(
                memoryMb = 2048,
                initialMemoryMb = 512,
                width = 1280,
                height = 720,
                extraJvmArgs = "-Duser=kept",
            )
        }
        service.importPack(
            Path.of(store.instance(instance.id).packFile),
            JobContext(store, "test"),
            instance.id,
        )
        val repaired = store.instance(instance.id)
        assertEquals(2048, repaired.memoryMb)
        assertEquals(512, repaired.initialMemoryMb)
        assertEquals(1280, repaired.width)
        assertEquals(720, repaired.height)
        assertEquals("-Duser=kept", repaired.extraJvmArgs)
    }
}
