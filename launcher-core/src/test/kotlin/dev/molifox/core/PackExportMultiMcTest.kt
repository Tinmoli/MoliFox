// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证 MultiMC/Prism 导出的实例设置、名称截断与加载器版本前置校验。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackExportMultiMcTest : PackExportTestSupport() {
    @Test
    fun `multimc export writes instance settings that the import side reads back`() =
        runBlocking {
            fixture { store, instance, root ->
                store.updateInstance(instance.id) {
                    it.copy(
                        memoryMb = 4096,
                        initialMemoryMb = 1024,
                        extraJvmArgs = "-Dexport.message=kept",
                        width = 1600,
                        height = 900,
                    )
                }
                write(root, "config/a.toml", "cfg")
                write(root, "mods/local.jar", "jar")
                NetworkClient().use { network ->
                    assertExportedSettings(store, instance, service(store, network))
                }
            }
        }

    private suspend fun assertExportedSettings(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
        val target =
            exportMultiMc(
                store,
                instance,
                service,
                temporary.resolve("settings.zip"),
                metadata,
            )
        ZipFile(target.toFile()).use { zip ->
            val config = instanceConfigValues(zip)
            assertEquals("true", config["OverrideMemory"])
            assertEquals("4096", config["MaxMemAlloc"])
            assertEquals("1024", config["MinMemAlloc"])
            assertEquals("true", config["OverrideJavaArgs"])
            assertEquals("-Dexport.message=kept", config["JvmArgs"])
            assertEquals("1600", config["MinecraftWinWidth"])
            assertEquals("900", config["MinecraftWinHeight"])
            val parsed = readPackManifest(zip, temporary.resolve("import"), "windows")
            assertEquals(PackInstanceDefaults(4096, 1024, 1600, 900), parsed.instanceDefaults)
            val jvm =
                requireNotNull(
                    parsed.launchOptions?.resolve(
                        LauncherSettings(extraJvmArgs = "-Dglobal.message=global"),
                        "windows",
                    ),
                ).jvm
            assertEquals(listOf("-Dexport.message=kept"), splitArguments(requireNotNull(jvm)))
        }
    }

    /** 导出一个 MultiMC 包：勾选全部内容，本地模组按已确认分发处理。 */
    private suspend fun exportMultiMc(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
        target: Path,
        exportMetadata: MrpackExportMetadata,
    ): Path {
        val plan = service.previewMrpackExport(instance.id, exportMetadata, false)
        val options = PackExportOptions(includeLocalResources = true)
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export"),
            plan.files.map { it.path }.toSet(),
            setOf("mods/local.jar"),
            PackExportFormat.MultiMc,
            options,
        )
        return target
    }

    @Test
    fun `long pack names are truncated to the multimc instance name limit`() =
        runBlocking {
            val longName = "长".repeat(120)
            fixture { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val target =
                        exportMultiMc(
                            store,
                            instance,
                            service(store, network),
                            temporary.resolve("long-name.zip"),
                            MrpackExportMetadata(longName, "1.0"),
                        )
                    ZipFile(target.toFile()).use { zip ->
                        val expected = "长".repeat(MAX_MULTIMC_INSTANCE_NAME_CHARS)
                        assertEquals(expected, instanceConfigValues(zip)["name"])
                        assertEquals(
                            expected,
                            readPackManifest(zip, temporary.resolve("import"), "windows")
                                .request.name,
                        )
                    }
                }
            }
        }

    @Test
    fun `multimc preview refuses an instance without a loader version`() =
        runBlocking {
            fixture(LoaderType.Forge, "") { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val failure =
                        assertFailsWith<IllegalArgumentException> {
                            service(store, network)
                                .previewMrpackExport(instance.id, metadata, false)
                        }
                    assertTrue(failure.message.orEmpty().contains("加载器"))
                }
            }
        }
}
