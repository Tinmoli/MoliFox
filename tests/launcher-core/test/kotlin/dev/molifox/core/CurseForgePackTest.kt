// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 CurseForge 整合包的预览、暂存、导入与修复。

package dev.molifox.core

import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 整合包清单校验、选装文件、自定义覆盖与失败修复的行为与边界。 */
class CurseForgePackTest : CurseForgeTestBase() {
    @Test
    fun `CurseForge preview is offline and reads loader optional files and custom overrides`() =
        fixture {
            val archive = pack(manifest(), mapOf("custom-overrides/config/a.txt" to "setting"))
            val before = store.data.instances
            val preview = service.previewPack(archive)
            assertEquals("CF Pack", preview.title)
            assertEquals(LoaderType.Fabric, preview.loader)
            assertEquals("0.16.14", preview.loaderVersion)
            assertEquals(listOf("curseforge:2:21"), preview.optionalFiles)
            assertEquals(1, preview.requiredFiles)
            assertEquals(before, store.data.instances)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `bad CurseForge manifests fail before network or instance creation`() =
        fixture {
            val samples =
                listOf(
                    manifest().apply { addProperty("manifestVersion", 2) },
                    manifest().apply { addProperty("overrides", "../escape") },
                    manifest().apply { array("files").add(array("files").first().deepCopy()) },
                    manifest().apply {
                        array(
                            "files",
                        ).first().asJsonObject.addProperty("fileID", -1)
                    },
                    manifest().apply { array("files").first().asJsonObject.remove("required") },
                    manifest().apply {
                        obj("minecraft")
                            .array("modLoaders")
                            .first()
                            .asJsonObject
                            .addProperty("id", "unsupported-1")
                    },
                )
            for (sample in samples) assertFails { service.previewPack(pack(sample)) }
            assertFails {
                service.previewPack(
                    pack(manifest(), mapOf("custom-overrides/../../escape.txt" to "bad")),
                )
            }
            assertFails {
                service.previewPack(
                    pack(manifest(), mapOf("custom-overrides/.molifox-content.json" to "bad")),
                )
            }
            assertEquals(1, store.data.instances.size)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `CurseForge pack staging respects optional selection and commits overrides`() =
        fixture {
            val root = temporary.resolve("pack-game").apply { createDirectories() }
            val archive = pack(manifest(), mapOf("custom-overrides/config/a.txt" to "setting"))
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(archive.toFile()).use { zip ->
                    val info = readPackManifest(zip, root, "windows")
                    val files =
                        prepareCurseForgePack(
                            info,
                            backend,
                            emptySet(),
                            temporary.resolve("manual-downloads"),
                            JobContext(store, "test"),
                        )
                    assertEquals(listOf("mods/11.jar"), files.map { it.path })
                    stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                        network.download(it)
                    }
                    stagePackFiles(
                        info,
                        zip,
                        transaction,
                        root,
                        emptySet(),
                        false,
                        JobContext(store, "test"),
                    ) {
                        network.download(it)
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals("file11", root.resolve("mods/11.jar").readText())
                assertEquals("setting", root.resolve("config/a.txt").readText())
                assertFalse(root.resolve("mods/21.jar").exists())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `manual pack files require exact hashes and are not modified`() =
        fixture {
            mode = "blocked"
            val archive = pack(manifest())
            val root = temporary.resolve("manual-game").apply { createDirectories() }
            val manual = temporary.resolve("manual-downloads").apply { createDirectories() }
            ZipFile(archive.toFile()).use { zip ->
                val info = readPackManifest(zip, root, "windows")
                prepareAndCommitManualPack(info, root, manual)
            }
            assertTrue(requests.none { it.path == "/files/21" })
        }

    @Test
    fun `missing manual files fail full import before creating a new instance`() =
        fixture {
            mode = "blocked"
            val archive = pack(manifest())
            val before = store.data.instances
            assertFailsWith<ManualContentRequired> {
                service.importPack(archive, JobContext(store, "test"))
            }
            assertEquals(before, store.data.instances)
            assertFalse(store.dataDirectory.resolve("packs").exists())
            assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
        }

    @Test
    fun `manual files changed after preparation cannot be committed`() =
        fixture {
            mode = "blocked"
            val root = temporary.resolve("changed-game").apply { createDirectories() }
            val manual = temporary.resolve("manual-downloads").apply { createDirectories() }
            manual.resolve("21.jar").writeText("file21")
            val archive = pack(manifest())
            ZipFile(archive.toFile()).use { zip ->
                val info = readPackManifest(zip, root, "windows")
                val files =
                    prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
                manual.resolve("21.jar").writeText("fileXX")
                val transaction = PackFileTransaction(store, root)
                try {
                    assertFails {
                        stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                            network.download(it)
                        }
                    }
                    assertFalse(root.resolve("mods/11.jar").exists())
                    assertFalse(root.resolve("mods/21.jar").exists())
                } finally {
                    transaction.cleanup()
                }
            }
        }

    @Test
    fun `CurseForge full import keeps isolation and failed pack can be repaired`() =
        fixture {
            resourcePack = true
            store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
            val archive =
                pack(
                    manifest().apply {
                        obj("minecraft").add("modLoaders", gson.toJsonTree(emptyList<String>()))
                    },
                    mapOf("custom-overrides/config/a.txt" to "setting"),
                )
            val before = store.data.instances.size
            mode = "download-fail"
            assertFails { service.importPack(archive, JobContext(store, "test")) }
            val failed = store.data.instances.last()
            assertEquals(before + 1, store.data.instances.size)
            assertEquals(VersionIsolation.Inherit, failed.isolation)
            assertFalse(failed.installed)
            val root = store.instancePath(failed.id)
            assertFalse(root.resolve("resourcepacks/11.zip").exists())
            assertTrue(root.listDirectoryEntries(".molifox-pack-*").isEmpty())
            mode = "normal"
            service.importPack(Path.of(failed.packFile), JobContext(store, "test"), failed.id)
            assertEquals(before + 1, store.data.instances.size)
            assertTrue(store.instance(failed.id).installed)
            assertEquals("file11", root.resolve("resourcepacks/11.zip").readText())
            assertEquals("file21", root.resolve("resourcepacks/21.zip").readText())
            root.resolve("config/a.txt").writeText("user setting")
            service.importPack(Path.of(failed.packFile), JobContext(store, "test"), failed.id)
            assertEquals("user setting", root.resolve("config/a.txt").readText())
        }

    @Test
    fun `repair reuses hash verified existing restricted files`() =
        fixture {
            mode = "blocked"
            val root =
                temporary
                    .resolve(
                        "repair-game",
                    ).apply { resolve("mods").createDirectories() }
            root.resolve("mods/21.jar").writeText("file21")
            val archive = pack(manifest())
            ZipFile(archive.toFile()).use { zip ->
                val info = readPackManifest(zip, root, "windows")
                val files =
                    prepareCurseForgePack(
                        info,
                        backend,
                        null,
                        temporary.resolve("missing-manual"),
                        JobContext(store, "test"),
                        root,
                    )
                assertEquals(
                    root.resolve("mods/21.jar"),
                    checkNotNull(files.find { it.path == "mods/21.jar" }).local,
                )
            }
        }

    /** 手工文件先按哈希校验：改错会被拒绝，改对后提交到实例且不改动下载目录。 */
    private suspend fun CurseForgeFixture.prepareAndCommitManualPack(
        info: PackManifest,
        root: Path,
        manual: Path,
    ) {
        assertEquals(
            "21.jar",
            assertFailsWith<ManualContentRequired> {
                prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
            }.files
                .single()
                .filename,
        )
        manual.resolve("21.jar").writeText("wrong!")
        assertFailsWith<ManualContentRequired> {
            prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
        }
        manual.resolve("21.jar").writeText("file21")
        val files =
            prepareCurseForgePack(info, backend, null, manual, JobContext(store, "test"))
        val transaction = PackFileTransaction(store, root)
        try {
            stageCurseForgePackFiles(files, transaction, JobContext(store, "test")) {
                network.download(it)
            }
            transaction.commit(JobContext(store, "test"))
            assertEquals("file21", root.resolve("mods/21.jar").readText())
            assertEquals("file21", manual.resolve("21.jar").readText())
        } finally {
            transaction.cleanup()
        }
    }
}
