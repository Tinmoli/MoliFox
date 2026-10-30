// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证多格式整合包导入的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackImportTest {
    @TempDir lateinit var temporary: Path
    private val bytes = "downloaded pack content".toByteArray()

    @Test
    fun `preview lists client optional files without creating an instance`() =
        runBlocking {
            val store = fixtureStore(temporary)
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                val source =
                    fixturePack(
                        temporary,
                        fixtureIndex(
                            listOf(
                                fixturePackFile(bytes, "mods/a.jar"),
                                fixturePackFile(bytes, "mods/b.jar", "optional"),
                                fixturePackFile(bytes, "mods/c.jar", "unsupported"),
                            ),
                        ),
                    )
                val preview = service.previewPack(source)
                assertEquals(listOf("mods/b.jar"), preview.optionalFiles)
                assertEquals(1, preview.requiredFiles)
                assertEquals("fixture", preview.gameVersion)
                assertEquals(64, preview.hash.length)
                assertTrue(store.data.instances.isEmpty())
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
            }
        }

    @Test
    fun `bad manifests and overrides fail before creating an instance`() =
        runBlocking {
            val store = fixtureStore(temporary)
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                assertBadManifestsRejected(store, service)
                assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
                assertFalse(temporary.resolve("escape.txt").exists())
            }
        }

    /** 每个坏清单样本都必须同时被预览与导入拒绝，且不留下实例。 */
    private suspend fun assertBadManifestsRejected(
        store: LauncherStore,
        service: ContentService,
    ) {
        for (source in badManifestSamples()) {
            assertFails { service.previewPack(source) }
            assertFails { service.importPack(source, JobContext(store, "test")) }
            assertTrue(store.data.instances.isEmpty())
        }
    }

    /** 格式版本、缺路径字段的样本，再拼上路径安全相关的样本；顺序与逐条拒绝一致。 */
    private fun badManifestSamples(): List<Path> = malformedFileSamples() + unsafePathSamples()

    /** 格式版本、缺哈希、负体积、非法 env、重复路径与父子冲突。 */
    private fun malformedFileSamples(): List<Path> {
        val missingHash =
            fixturePackFile(bytes, "a.txt").apply { obj("hashes").remove("sha512") }
        val badSize = fixturePackFile(bytes, "a.txt").apply { addProperty("fileSize", -1) }
        val badEnv =
            fixturePackFile(bytes, "a.txt").apply {
                obj("env").addProperty("client", "invalid")
            }
        return listOf(
            fixturePack(
                temporary,
                fixtureIndex().apply { addProperty("formatVersion", 1.5) },
            ),
            fixturePack(temporary, fixtureIndex(listOf(missingHash))),
            fixturePack(temporary, fixtureIndex(listOf(badSize))),
            fixturePack(temporary, fixtureIndex(listOf(badEnv))),
            fixturePack(
                temporary,
                fixtureIndex(
                    listOf(
                        fixturePackFile(bytes, "same.txt"),
                        fixturePackFile(bytes, "same.txt"),
                    ),
                ),
            ),
            fixturePack(
                temporary,
                fixtureIndex(
                    listOf(
                        fixturePackFile(bytes, "config"),
                        fixturePackFile(bytes, "config/child.txt"),
                    ),
                ),
            ),
        )
    }

    /** 越界覆盖与覆盖账本都必须被拒绝。 */
    private fun unsafePathSamples(): List<Path> =
        listOf(
            fixturePack(
                temporary,
                fixtureIndex(),
                mapOf("overrides/../../escape.txt" to bytes),
            ),
            fixturePack(
                temporary,
                fixtureIndex(),
                mapOf("client-overrides/.MOLIFOX-content.json" to bytes),
            ),
        )

    @Test
    fun `changed preview and unknown optional choice are rejected before installation`() =
        runBlocking {
            val store = fixtureStore(temporary)
            NetworkClient().use { network ->
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                val source = fixturePack(temporary, fixtureIndex())
                val preview = service.previewPack(source)
                source.writeBytes(
                    fixtureZipBytes(
                        mapOf(
                            "modrinth.index.json" to
                                gson
                                    .toJson(
                                        fixtureIndex().apply { addProperty("name", "Changed") },
                                    ).toByteArray(),
                        ),
                    ),
                )
                assertFails {
                    service.importPack(
                        source,
                        JobContext(store, "test"),
                        expectedHash = preview.hash,
                    )
                }
                assertFails {
                    service.importPack(
                        source,
                        JobContext(store, "test"),
                        optionalFiles = setOf("not-in-pack.jar"),
                    )
                }
                assertTrue(store.data.instances.isEmpty())
            }
        }

    @Test
    fun `staging skips unselected and server files and applies client overrides last`() =
        runBlocking {
            val store = fixtureStore(temporary)
            val root = temporary.resolve("game").apply { createDirectories() }
            val source =
                fixturePack(
                    temporary,
                    fixtureIndex(
                        listOf(
                            fixturePackFile(bytes, "mods/a.jar"),
                            fixturePackFile(bytes, "mods/b.jar", "optional"),
                            fixturePackFile(bytes, "mods/c.jar", "unsupported"),
                        ),
                    ),
                    mapOf(
                        "overrides/config/test.txt" to "base".toByteArray(),
                        "client-overrides/config/test.txt" to "client".toByteArray(),
                        "server-overrides/config/test.txt" to "server".toByteArray(),
                    ),
                )
            val transaction = PackFileTransaction(store, root)
            val downloaded = mutableListOf<String>()
            try {
                stageFixture(source, store, root, transaction, downloaded)
                assertEquals(listOf("a.jar"), downloaded)
                assertEquals("client", root.resolve("config/test.txt").readText())
                assertFalse(root.resolve("mods/b.jar").exists())
                assertFalse(root.resolve("mods/c.jar").exists())
            } finally {
                transaction.cleanup()
            }
        }

    /** 以空选择集暂存：只下载必填的客户端文件，client-overrides 最后覆盖。 */
    private suspend fun stageFixture(
        source: Path,
        store: LauncherStore,
        root: Path,
        transaction: PackFileTransaction,
        downloaded: MutableList<String>,
    ) {
        ZipFile(source.toFile()).use { zip ->
            stagePackFiles(
                readPackManifest(zip, root, store.platform.osName),
                zip,
                transaction,
                root,
                emptySet(),
                false,
                JobContext(store, "test"),
            ) {
                downloaded += it.target.fileName.toString()
                it.target.parent.createDirectories()
                it.target.writeBytes(bytes)
            }
        }
        transaction.commit(JobContext(store, "test"))
    }

    @Test
    fun `both hashes and size are checked and download mirrors can recover`() =
        runBlocking {
            val store = fixtureStore(temporary)
            val root = temporary.resolve("game").apply { createDirectories() }
            assertRejectsCorruptedEntries(store, root)
            assertMirrorRecovery(store, root)
        }

    /** 哈希不符与体积不符都必须拒绝落盘，且不留下半个文件。 */
    private suspend fun assertRejectsCorruptedEntries(
        store: LauncherStore,
        root: Path,
    ) {
        for (kind in listOf("sha1", "sha512", "fileSize")) {
            val entry = fixturePackFile(bytes, "a.txt")
            if (kind == "fileSize") {
                entry.addProperty(kind, bytes.size + 1)
            } else {
                val length = if (kind == "sha1") 40 else 128
                entry.obj("hashes").addProperty(kind, "0".repeat(length))
            }
            val source = fixturePack(temporary, fixtureIndex(listOf(entry)))
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    assertFails {
                        stagePackFiles(
                            readPackManifest(zip, root, store.platform.osName),
                            zip,
                            transaction,
                            root,
                            null,
                            false,
                            JobContext(store, "test"),
                        ) {
                            it.target.parent.createDirectories()
                            it.target.writeBytes(bytes)
                        }
                    }
                }
                assertFalse(root.resolve("a.txt").exists())
            } finally {
                transaction.cleanup()
            }
        }
    }

    /** 首个镜像失败后换下一个镜像重试，最终落盘内容与字节一致。 */
    private suspend fun assertMirrorRecovery(
        store: LauncherStore,
        root: Path,
    ) {
        val source = fixturePack(temporary, fixtureIndex(listOf(fixtureMirrorEntry(bytes))))
        val transaction = PackFileTransaction(store, root)
        var attempts = 0
        try {
            ZipFile(source.toFile()).use { zip ->
                stagePackFiles(
                    readPackManifest(zip, root, store.platform.osName),
                    zip,
                    transaction,
                    root,
                    null,
                    false,
                    JobContext(store, "test"),
                ) {
                    attempts++
                    if (attempts == 1) throw java.io.IOException("Fixture mirror unavailable")
                    it.target.parent.createDirectories()
                    it.target.writeBytes(bytes)
                }
            }
            transaction.commit(JobContext(store, "test"))
            assertEquals(2, attempts)
            assertContentEquals(bytes, root.resolve("a.txt").readBytes())
        } finally {
            transaction.cleanup()
        }
    }

    @Test
    fun `commit failure restores files already replaced`() =
        runBlocking {
            val store = fixtureStore(temporary)
            val root = temporary.resolve("game").apply { createDirectories() }
            root.resolve("a.txt").writeText("user file")
            root.resolve("z.txt").createDirectories()
            val transaction = PackFileTransaction(store, root)
            try {
                transaction.content.resolve("a.txt").writeText("replacement")
                transaction.content.resolve("z.txt").writeText("collision")
                assertFails { transaction.commit(JobContext(store, "test")) }
                assertEquals("user file", root.resolve("a.txt").readText())
                assertTrue(root.resolve("z.txt").isDirectory())
            } finally {
                transaction.cleanup()
            }
        }

    @Test
    fun `pack respects the shared directory and repairs without a second instance`() =
        runBlocking {
            val store = fixtureStore(temporary)
            store.mutate { it.copy(settings = it.settings.copy(isolateVersions = false)) }
            val client = fixtureZipBytes(mapOf("fixture.txt" to bytes))
            var rejectClient = true
            fixtureServer(client) { rejectClient }.use { server ->
                val base = server.loopbackUrl("/").toString().removeSuffix("/")
                NetworkClient().use { network ->
                    val installer =
                        MinecraftInstaller(
                            store,
                            network,
                            JavaService(store, network),
                            "$base/manifest.json",
                        )
                    val service = ContentService(store, network, installer)
                    val source =
                        fixturePack(
                            temporary,
                            fixtureIndex(),
                            mapOf("overrides/config/fixture.txt" to "pack config".toByteArray()),
                        )
                    assertFails {
                        service.importPack(
                            source,
                            JobContext(store, "test"),
                            optionalFiles = emptySet(),
                        )
                    }
                    val failed = store.data.instances.single()
                    assertFalse(failed.installed)
                    assertEquals(VersionIsolation.Inherit, failed.isolation)
                    assertTrue(Path.of(failed.packFile).isRegularFile())
                    rejectClient = false
                    assertRepairedInPlace(store, service, failed)
                }
            }
        }

    /** 修好镜像后按同一实例重新导入：不新建实例，并保留整合包里的配置。 */
    private suspend fun assertRepairedInPlace(
        store: LauncherStore,
        service: ContentService,
        failed: GameInstance,
    ) {
        service.importPack(
            Path.of(failed.packFile),
            JobContext(store, "test"),
            repairId = failed.id,
        )
        assertEquals(1, store.data.instances.size)
        assertTrue(store.instance(failed.id).installed)
        assertEquals(emptyList(), store.instance(failed.id).packOptionalFiles)
        assertEquals(
            "pack config",
            store.instancePath(failed.id).resolve("config/fixture.txt").readText(),
        )
        assertTrue(store.minecraftRoot.resolve("config/fixture.txt").exists())
        assertTrue(store.cacheDirectory.listDirectoryEntries("pack-import-*").isEmpty())
    }
}
