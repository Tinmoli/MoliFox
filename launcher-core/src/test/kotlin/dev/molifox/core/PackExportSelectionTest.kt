// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证导出选择项：可选开关、默认勾选、顶层条目扫描与空选择拒绝。

package dev.molifox.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.extension
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackExportSelectionTest : PackExportTestSupport() {
    @Test
    fun `optional switches drive the default selection`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "options.txt", "cfg")
                write(root, "config/a.toml", "cfg")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val base =
                        exportDefaultSelection(plan, PackExportFormat.Modrinth, PackExportOptions())
                    assertEquals(setOf("config/a.toml"), base)
                    val all =
                        exportDefaultSelection(
                            plan,
                            PackExportFormat.Modrinth,
                            PackExportOptions(
                                includeSaves = true,
                                includeScreenshots = true,
                                includeOptionsTxt = true,
                            ),
                        )
                    assertEquals(
                        setOf(
                            "config/a.toml",
                            "saves/World/level.dat",
                            "screenshots/a.png",
                            "options.txt",
                        ),
                        all,
                    )
                }
            }
        }

    @Test
    fun `selectable entries cover every direct child and hide launcher data`() =
        runBlocking {
            fixture { store, instance, root ->
                writeSelectableEntriesFixture(root, instance)
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val scan = service.exportEntries(instance.id).last()
                    assertSelectableEntryNames(scan)
                    assertSelectableEntryStats(scan)
                }
            }
        }

    private fun writeSelectableEntriesFixture(
        root: Path,
        instance: GameInstance,
    ) {
        write(root, "mods/a.jar", "jar-bytes")
        write(root, "mods/1.12.2/b.jar", "jar2")
        write(root, "config/a.toml", "cfg")
        write(root, "resourcepacks/rp.zip", "rp")
        write(root, "saves/World/level.dat", "save")
        write(root, "screenshots/a.png", "png")
        write(root, "customnpcs/scripts/npc.json", "npc")
        write(root, "journeymap/data/map.bin", "map")
        write(root, "cache/tmp.bin", "cache")
        write(root, ".mixin.out/x.class", "mix")
        write(root, "options.txt", "opt")
        write(root, "servers.dat", "srv")
        write(root, "loose.dat", "loose")
        write(root, "logs/latest.log", "log!")
        write(root, "accounts/acc.json", "SECRET")
        write(root, "credentials.json", "SECRET")
        write(root, "launcher_profiles.json", "{}")
        write(root, "usercache.json", "[]")
        write(root, "MoliFox/internal.json", "SECRET")
        write(root, ".molifox-cache/x", "SECRET")
        write(root, "pending.part", "partial")
        write(root, "${instance.versionId}.json", "{}")
        write(root, "${instance.versionId}.jar", "jar")
        write(root, "${instance.versionId}.json.molifox-backup-20260101-000000", "{}")
    }

    private fun assertSelectableEntryNames(scan: PackExportScan) {
        assertEquals(
            listOf(
                ".mixin.out",
                "cache",
                "config",
                "customnpcs",
                "journeymap",
                "logs",
                "mods",
                "resourcepacks",
                "saves",
                "screenshots",
                "loose.dat",
                "options.txt",
                "servers.dat",
            ),
            scan.entries.map { it.name },
        )
        assertEquals(
            setOf(
                ".molifox-cache",
                "1.20.1.jar",
                "1.20.1.json",
                "1.20.1.json.molifox-backup-20260101-000000",
                "MoliFox",
                "accounts",
                "credentials.json",
                "launcher_profiles.json",
                "pending.part",
                "usercache.json",
            ),
            scan.omitted.toSet(),
        )
    }

    private fun assertSelectableEntryStats(scan: PackExportScan) {
        assertTrue(scan.entries.none { it.scanning })
        val mods = scan.entries.single { it.name == "mods" }
        assertEquals(2L, mods.fileCount)
        assertEquals(("jar-bytes".length + "jar2".length).toLong(), mods.totalBytes)
        val options = scan.entries.single { it.name == "options.txt" }
        assertFalse(options.directory)
        assertEquals("opt".length.toLong(), options.totalBytes)
        val logs = scan.entries.single { it.name == "logs" }
        assertTrue(logs.backupOnly)
        assertEquals(1L, logs.fileCount)
        assertEquals("log!".length.toLong(), logs.totalBytes)
    }

    @Test
    fun `default checked entries are the five portable ones for every format`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "resourcepacks/rp.zip", "rp")
                write(root, "shaderpacks/sp.zip", "sp")
                write(root, "options.txt", "opt")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "servers.dat", "srv")
                write(root, "customnpcs/scripts/npc.json", "npc")
                write(root, "logs/latest.log", "log")
                write(root, ".mixin.out/x", "mix")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val scan = service.exportEntries(instance.id).last()
                    val defaults = defaultExportEntries(scan.entries)
                    assertEquals(
                        setOf("mods", "config", "resourcepacks", "shaderpacks", "options.txt"),
                        defaults,
                    )
                    // 手动改过的行在切换格式后保持原样：默认值与格式无关，不重新套用。
                    val manual = (defaults - "resourcepacks") + "saves"
                    for (format in PackExportFormat.entries) {
                        assertEquals(manual, retainExportSelection(scan.entries, manual))
                    }
                    assertFalse("saves" in defaults)
                    assertFalse("logs" in defaults)
                    assertFalse(".mixin.out" in defaults)
                    assertFalse("customnpcs" in defaults)
                }
            }
        }

    @Test
    fun `selected top level entries define exactly the archive payload`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    assertSubsetArchive(store, instance, service(store, network))
                }
            }
        }

    private suspend fun assertSubsetArchive(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
        val plan = service.previewMrpackExport(instance.id, metadata, false)
        val options = PackExportOptions(includeLocalResources = true)
        val selection =
            exportSelectedPaths(
                plan,
                PackExportFormat.MultiMc,
                options,
                setOf("mods", "options.txt"),
            )
        val target = temporary.resolve("subset.zip")
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export"),
            selection,
            setOf("mods/a.jar"),
            PackExportFormat.MultiMc,
            options,
        )
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                setOf(
                    "instance.cfg",
                    "mmc-pack.json",
                    ".minecraft/",
                    ".minecraft/mods/a.jar",
                    ".minecraft/options.txt",
                ),
                listing(zip),
            )
        }
    }

    @Test
    fun `backup without saves contains no saves entries`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "config/a.toml", "cfg")
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png", "png")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val scan = service.exportEntries(instance.id).last()
                    val keys = scan.entries.map { it.key }.toSet() - "saves"
                    val options = PackExportOptions.defaultsFor(PackExportFormat.Backup)
                    val selection =
                        exportSelectedPaths(plan, PackExportFormat.Backup, options, keys)
                    val target = temporary.resolve("no-saves.zip")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "export"),
                        selection,
                        emptySet(),
                        PackExportFormat.Backup,
                        options,
                    )
                    ZipFile(target.toFile()).use { zip ->
                        val names = listing(zip)
                        assertFalse("minecraft/saves/World/level.dat" in names)
                        assertTrue(names.none { it.startsWith("minecraft/saves/") })
                        assertTrue("minecraft/mods/a.jar" in names)
                        assertTrue("minecraft/screenshots/a.png" in names)
                    }
                }
            }
        }

    @Test
    fun `empty selection is refused with a clear message`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "mods/a.jar", "jar")
                write(root, "options.txt", "opt")
                NetworkClient().use { network ->
                    val service = service(store, network)
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    for (format in listOf(PackExportFormat.Modrinth, PackExportFormat.Backup)) {
                        val failure =
                            assertFailsWith<IllegalArgumentException> {
                                service.exportMrpack(
                                    plan,
                                    temporary.resolve("empty.${format.extension}"),
                                    JobContext(store, "export"),
                                    emptySet(),
                                    emptySet(),
                                    format,
                                    PackExportOptions(includeLocalResources = true),
                                )
                            }
                        assertTrue(failure.message.orEmpty().contains("至少选择"))
                    }
                }
            }
        }
}
