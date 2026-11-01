// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证Modrinth 标准整合包的导出与文件识别的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val OFFLINE_VISIBLE =
    setOf(
        "config/a.toml",
        "defaultconfigs/b.toml",
        "kubejs/startup_scripts/test.js",
        "scripts/a.zs",
        "mods/local.jar",
        "saves/World/level.dat",
        "screenshots/a.png",
        "options.txt",
        "servers.dat",
    )

private val OFFLINE_EXCLUDED =
    setOf(
        "accounts.json",
        "credentials/one.json",
        "launcher.json",
        "launcher_accounts.json",
        "logs/latest.log",
        "assets/objects/abc",
        "libraries/a.jar",
        "versions/other/other.json",
        ".molifox-content.json",
        "config/nested/tokens/private.json",
        "1.20.1.jar",
        "1.20.1.json",
    )

private val OFFLINE_DEFAULT_SELECTION =
    setOf(
        "config/a.toml",
        "defaultconfigs/b.toml",
        "kubejs/startup_scripts/test.js",
        "scripts/a.zs",
    )

class MrpackExportTest : MrpackExportTestBase() {
    @Test
    fun `offline defaults exclude private runtime and user data while preserving configuration`() =
        runBlocking {
            fixture { store, service, instance, root ->
                (OFFLINE_VISIBLE + OFFLINE_EXCLUDED).forEach { write(root, it) }
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(OFFLINE_VISIBLE, plan.files.map { it.path }.toSet())
                assertEquals(OFFLINE_DEFAULT_SELECTION, plan.defaultSelection)
                val target = temporary.resolve("offline.mrpack")
                service.exportMrpack(plan, target, JobContext(store, "test"))
                assertOfflineArchive(plan, target)
            }
        }

    private fun assertOfflineArchive(
        plan: MrpackExportPlan,
        target: Path,
    ) {
        ZipFile(target.toFile()).use { zip ->
            assertEquals(
                plan.defaultSelection.map { "client-overrides/$it" }.toSet() +
                    "modrinth.index.json",
                zip
                    .entries()
                    .asSequence()
                    .map { it.name }
                    .toSet(),
            )
            val manifest =
                readPackManifest(zip, temporary.resolve("destination"), "windows")
            assertEquals(metadata.name, manifest.request.name)
            assertEquals(metadata.version, manifest.version)
            assertEquals(metadata.summary, manifest.summary)
            assertEquals("1.20.1", manifest.request.gameVersion)
        }
    }

    @Test
    fun `unknown local resources need explicit inclusion and permission`() =
        runBlocking {
            fixture { store, service, instance, root ->
                write(root, "mods/private.jar", "private bytes")
                write(root, "resourcepacks/local.zip")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertTrue(plan.defaultSelection.isEmpty())
                assertTrue(plan.files.all { it.requiresPermission })
                val selected = plan.files.map { it.path }.toSet()
                val target = temporary.resolve("local.mrpack")
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"), selected)
                }
                assertFalse(target.exists())
                service.exportMrpack(plan, target, JobContext(store, "test"), selected, selected)
                ZipFile(target.toFile()).use { zip ->
                    assertEquals(
                        "private bytes",
                        zip
                            .getInputStream(zip.getEntry("client-overrides/mods/private.jar"))
                            .reader()
                            .readText(),
                    )
                }
                assertTrue(root.resolve("mods/private.jar").exists())
            }
        }

    @Test
    fun `changed and missing files preserve existing output and remove partial archives`() =
        runBlocking {
            fixture { store, service, instance, root ->
                val source = write(root, "config/a.txt", "old")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                val target =
                    temporary.resolve("existing.mrpack").apply { writeText("original archive") }
                source.writeText("new")
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertEquals("original archive", target.readText())
                source.deleteExisting()
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertEquals("original archive", target.readText())
                assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
            }
        }

    @Test
    fun `component changes invalidate a prepared export`() =
        runBlocking {
            fixture { store, service, instance, root ->
                write(root, "config/a.txt")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                store.updateInstance(instance.id) {
                    it.copy(loader = LoaderType.Fabric, loaderVersion = "0.16.14")
                }
                val target = temporary.resolve("changed.mrpack")
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(plan, target, JobContext(store, "test"))
                }
                assertFalse(target.exists())
            }
        }

    @Test
    fun `output inside game or launcher data and invalid selections are refused`() =
        runBlocking {
            fixture { store, service, instance, root ->
                write(root, "config/a.txt")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                for (
                target in
                listOf(
                    root.resolve("out.mrpack"),
                    store.minecraftRoot.resolve("out.mrpack"),
                    store.dataDirectory.resolve("out.mrpack"),
                    temporary.resolve("out.zip"),
                )
                ) {
                    assertFailsWith<IllegalArgumentException> {
                        service.exportMrpack(plan, target, JobContext(store, "test"))
                    }
                    assertFalse(target.exists())
                }
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(
                        plan,
                        temporary.resolve("out.mrpack"),
                        JobContext(store, "test"),
                        setOf("../private.json"),
                    )
                }
            }
        }

    @Test
    fun `cancelled export creates no output or temporary archive`() =
        runBlocking {
            fixture { store, service, instance, root ->
                write(root, "config/a.txt")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                val target = temporary.resolve("cancelled.mrpack")
                val job = Job().apply { cancel() }
                assertFailsWith<CancellationException> {
                    withContext(
                        job,
                    ) { service.exportMrpack(plan, target, JobContext(store, "test")) }
                }
                assertFalse(target.exists())
                assertTrue(temporary.listDirectoryEntries("*.part").isEmpty())
            }
        }

    @Test
    fun `all four loader dependencies and vanilla round trip without launcher specific metadata`() =
        runBlocking {
            fixture { store, service, instance, _ ->
                write(store.instancePath(instance.id), "config/example.txt")
                val compatible =
                    listOf(
                        LoaderType.Vanilla,
                        LoaderType.Fabric,
                        LoaderType.Quilt,
                        LoaderType.Forge,
                        LoaderType.NeoForge,
                    )
                for (loader in compatible) {
                    store.updateInstance(instance.id) {
                        it.copy(
                            loader = loader,
                            loaderVersion = if (loader == LoaderType.Vanilla) "" else "1.2.3",
                        )
                    }
                    val plan = service.previewMrpackExport(instance.id, metadata, false)
                    val target = temporary.resolve("${loader.name}.mrpack")
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "test"),
                        selected = plan.files.map { it.path }.toSet(),
                    )
                    val preview = service.previewPack(target)
                    assertEquals(loader, preview.loader)
                    assertEquals(
                        if (loader == LoaderType.Vanilla) "" else "1.2.3",
                        preview.loaderVersion,
                    )
                }
            }
        }

    @Test
    fun `shared directory exports user content and explicit external loader versions`() =
        runBlocking {
            fixture { store, service, instance, _ ->
                // 共用游戏目录：只有实例自己把隔离设为「关闭」才会落到游戏根；全局开关不再覆盖实例值。
                store.updateInstance(instance.id) {
                    it.copy(isolation = VersionIsolation.Disabled)
                }
                val root = store.instancePath(instance.id)
                write(root, "config/a.txt")
                write(root, "OtherLauncher/settings.json")
                write(root, "unknown-file.txt")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(listOf("config/a.txt"), plan.files.map { it.path })
                store.updateInstance(instance.id) {
                    it.copy(
                        externalProfile = true,
                        loader = LoaderType.Fabric,
                        loaderVersion = "0.16.14",
                    )
                }
                val external = service.previewMrpackExport(instance.id, metadata, false)
                assertEquals(LoaderType.Fabric, external.components.loader)
                assertEquals("0.16.14", external.components.loaderVersion)
                store.updateInstance(instance.id) {
                    it.copy(externalProfile = false, installed = false)
                }
                assertFailsWith<IllegalArgumentException> {
                    service.previewMrpackExport(instance.id, metadata, false)
                }
            }
        }

    @Test
    fun `user data can be selected explicitly while private credentials cannot`() =
        runBlocking {
            fixture { store, service, instance, root ->
                write(root, "saves/World/level.dat", "save")
                write(root, "screenshots/a.png")
                write(root, "credentials/account.json", "private")
                val plan = service.previewMrpackExport(instance.id, metadata, false)
                assertTrue(plan.defaultSelection.isEmpty())
                val target = temporary.resolve("selected.mrpack")
                service.exportMrpack(
                    plan,
                    target,
                    JobContext(store, "test"),
                    setOf("saves/World/level.dat"),
                )
                ZipFile(target.toFile()).use { zip ->
                    assertEquals(
                        setOf("modrinth.index.json", "client-overrides/saves/World/level.dat"),
                        zip
                            .entries()
                            .asSequence()
                            .map { it.name }
                            .toSet(),
                    )
                }
                assertFailsWith<IllegalArgumentException> {
                    service.exportMrpack(
                        plan,
                        target,
                        JobContext(store, "test"),
                        setOf("credentials/account.json"),
                    )
                }
            }
        }

    @Test
    fun `invalid package metadata and missing loader versions fail before export`() =
        runBlocking {
            fixture { store, service, instance, _ ->
                for (
                info in
                listOf(
                    metadata.copy(name = " "),
                    metadata.copy(version = ""),
                    metadata.copy(summary = "x".repeat(2001)),
                    metadata.copy(name = "bad\u0000name"),
                )
                ) {
                    assertFailsWith<IllegalArgumentException> {
                        service.previewMrpackExport(instance.id, info, false)
                    }
                }
                store.updateInstance(instance.id) {
                    it.copy(loader = LoaderType.Forge, loaderVersion = "")
                }
                assertFailsWith<IllegalArgumentException> {
                    service.previewMrpackExport(instance.id, metadata, false)
                }
            }
        }
}
