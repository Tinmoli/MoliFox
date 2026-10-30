// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证完整备份与日志的归档内容、私密文件排除和「备份不是整合包」的边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackExportBackupTest : PackExportTestSupport() {
    @Test
    fun `backup includes saves screenshots and optional logs but is not a modpack`() =
        runBlocking {
            fixture { store, instance, root ->
                writeBackupFixture(root)
                NetworkClient().use { network ->
                    val service = service(store, network)
                    assertBackupArchive(service, store, instance)
                    assertBackupLogs(service, store, instance)
                }
            }
        }

    private fun writeBackupFixture(root: Path) {
        write(root, "mods/a.jar", "jar")
        write(root, "config/a.toml", "cfg")
        write(root, "saves/World/level.dat", "save")
        write(root, "screenshots/a.png", "png")
        write(root, "options.txt", "guiScale:2")
        write(root, "logs/latest.log", "plain log line")
        write(root, "crash-reports/crash.txt", "crash")
        write(root, "credentials/account.json", "SECRET")
        write(root, "tokens/one.txt", "SECRET")
        write(root, "launcher_profiles.json", "{}")
        write(root, "usercache.json", "[]")
        write(root, "MoliFox/internal.json", "SECRET")
    }

    private suspend fun assertBackupArchive(
        service: ContentService,
        store: LauncherStore,
        instance: GameInstance,
    ) {
        val withoutLogs = service.previewMrpackExport(instance.id, metadata, false)
        assertTrue(withoutLogs.files.none { it.path.startsWith("logs/") })
        val defaults = PackExportOptions.defaultsFor(PackExportFormat.Backup)
        val selection =
            exportDefaultSelection(withoutLogs, PackExportFormat.Backup, defaults)
        assertEquals(
            setOf(
                "mods/a.jar",
                "config/a.toml",
                "saves/World/level.dat",
                "screenshots/a.png",
                "options.txt",
            ),
            selection,
        )
        val target = temporary.resolve("backup.zip")
        service.exportMrpack(
            withoutLogs,
            target,
            JobContext(store, "export"),
            selection,
            emptySet(),
            PackExportFormat.Backup,
            defaults,
        )
        ZipFile(target.toFile()).use { zip -> assertBackupPayload(zip) }
    }

    private fun assertBackupPayload(zip: ZipFile) {
        val names = listing(zip)
        assertEquals(
            setOf(
                "molifox-backup.json",
                "minecraft/",
                "minecraft/config/a.toml",
                "minecraft/mods/a.jar",
                "minecraft/options.txt",
                "minecraft/saves/World/level.dat",
                "minecraft/screenshots/a.png",
            ),
            names,
        )
        assertTrue(
            names.none {
                it.contains("credentials") ||
                    it.contains("tokens") ||
                    it.contains("launcher_profiles") ||
                    it.contains("usercache") ||
                    it.contains("MoliFox") ||
                    it.contains("logs/")
            },
        )
        val info = requireNotNull(packJson(zip, "molifox-backup.json"))
        assertEquals("molifox-backup", info.string("type"))
        assertEquals(5, info.array("files").size())
        // 完整备份不是整合包：不能按整合包读回。
        assertFailsWith<IllegalArgumentException> {
            readPackManifest(zip, temporary.resolve("import"), "windows")
        }
    }

    private suspend fun assertBackupLogs(
        service: ContentService,
        store: LauncherStore,
        instance: GameInstance,
    ) {
        // 勾选日志后才扫描并写入 logs/ 与 crash-reports/。
        val withLogs =
            service.previewMrpackExport(
                instance.id,
                metadata,
                false,
                includeLogs = true,
            )
        val logOptions =
            PackExportOptions
                .defaultsFor(PackExportFormat.Backup)
                .copy(includeLogs = true)
        val logTarget = temporary.resolve("backup-logs.zip")
        service.exportMrpack(
            withLogs,
            logTarget,
            JobContext(store, "export"),
            exportDefaultSelection(withLogs, PackExportFormat.Backup, logOptions),
            emptySet(),
            PackExportFormat.Backup,
            logOptions,
        )
        ZipFile(logTarget.toFile()).use { zip ->
            val names = listing(zip)
            assertTrue("minecraft/logs/latest.log" in names)
            assertTrue("minecraft/crash-reports/crash.txt" in names)
        }
        assertLogsRejectedForModpack(service, store, withLogs, logOptions)
    }

    /** 日志不能混进可导入的整合包格式。 */
    private suspend fun assertLogsRejectedForModpack(
        service: ContentService,
        store: LauncherStore,
        plan: MrpackExportPlan,
        options: PackExportOptions,
    ) {
        assertFailsWith<IllegalArgumentException> {
            service.exportMrpack(
                plan,
                temporary.resolve("not-backup.zip"),
                JobContext(store, "export"),
                setOf("logs/latest.log"),
                emptySet(),
                PackExportFormat.Mcbbs,
                options,
            )
        }
    }

    @Test
    fun `logs containing credentials are skipped and warned about`() =
        runBlocking {
            fixture { store, instance, root ->
                write(root, "config/a.toml", "cfg")
                write(root, "logs/latest.log", "accessToken=abcdefghijklmnopqrstuvwx")
                write(root, "logs/clean.log", "plain")
                NetworkClient().use { network ->
                    assertCredentialLogsSkipped(store, instance, service(store, network))
                }
            }
        }

    private suspend fun assertCredentialLogsSkipped(
        store: LauncherStore,
        instance: GameInstance,
        service: ContentService,
    ) {
        val plan =
            service.previewMrpackExport(
                instance.id,
                metadata,
                false,
                includeLogs = true,
            )
        assertTrue(plan.warnings.any { it.contains("登录凭据") })
        assertEquals(
            listOf("logs/clean.log"),
            plan.files.map { it.path }.filter { it.startsWith("logs/") },
        )
        val options =
            PackExportOptions
                .defaultsFor(PackExportFormat.Backup)
                .copy(includeLogs = true)
        val target = temporary.resolve("safe-backup.zip")
        service.exportMrpack(
            plan,
            target,
            JobContext(store, "export"),
            exportDefaultSelection(plan, PackExportFormat.Backup, options),
            emptySet(),
            PackExportFormat.Backup,
            options,
        )
        ZipFile(target.toFile()).use { zip ->
            val names = listing(zip)
            assertTrue("minecraft/logs/clean.log" in names)
            assertFalse("minecraft/logs/latest.log" in names)
        }
    }
}
