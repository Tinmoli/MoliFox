// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证资源包与光影更新落地：下载校验、旧文件保留、失败复位与文件名安全。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackUpdateInstallerTest {
    private val temporary: Path = workTestDirectory("launcher-core-pack-installer")
    private val trashed = mutableListOf<Path>()

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    @Test
    fun `successful update replaces the old zip and moves it to trash`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val folder = PackFolder.ResourcePacks
            val old = packFile(store, instance, folder, "FoxPack-1.0.zip")
            val bytes = zipBytes("new")
            val item = itemFor(folder, old, candidate("FoxPack-1.1.zip", bytes))
            PackUpdateService(store, emptyList(), downloader(bytes)).apply(instance.id, item)
            val directory = store.instancePath(instance.id).resolve(folder.directory)
            assertFalse(old.exists())
            assertTrue(directory.resolve("FoxPack-1.1.zip").readBytes().contentEquals(bytes))
            assertEquals(1, trashed.size)
        }

    @Test
    fun `hash mismatch keeps the old pack file`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ShaderPacks, "FoxShader.zip")
            val before = old.readBytes()
            val bytes = zipBytes("new")
            val candidate = candidate("FoxShader.zip", bytes, sha512 = "0".repeat(128))
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PackUpdateService(store, emptyList(), downloader(bytes))
                        .apply(instance.id, itemFor(PackFolder.ShaderPacks, old, candidate))
                }
            assertEquals("更新文件哈希不符", failure.message)
            assertTrue(old.readBytes().contentEquals(before))
            assertEquals(0, trashed.size)
            assertNoTemporaryFiles(store, instance, PackFolder.ShaderPacks)
        }

    @Test
    fun `size mismatch keeps the old pack file`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val before = old.readBytes()
            val bytes = zipBytes("new")
            val candidate = candidate("FoxPack.zip", bytes, size = bytes.size + 1L)
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PackUpdateService(store, emptyList(), downloader(bytes))
                        .apply(instance.id, itemFor(PackFolder.ResourcePacks, old, candidate))
                }
            assertEquals("更新文件大小不符", failure.message)
            assertTrue(old.readBytes().contentEquals(before))
        }

    @Test
    fun `downloaded payload that is not a zip keeps the old pack file`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val before = old.readBytes()
            val bytes = "not a zip".toByteArray()
            val candidate = candidate("FoxPack-2.zip", bytes)
            assertFailsWith<IOException> {
                PackUpdateService(store, emptyList(), downloader(bytes))
                    .apply(instance.id, itemFor(PackFolder.ResourcePacks, old, candidate))
            }
            assertTrue(old.readBytes().contentEquals(before))
            assertNoTemporaryFiles(store, instance, PackFolder.ResourcePacks)
        }

    @Test
    fun `download failure never touches the old pack file`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val before = old.readBytes()
            val bytes = zipBytes("new")
            val failing: PackUpdateDownloader = { _, _, _ -> throw IOException("网络中断") }
            val failure =
                assertFailsWith<IOException> {
                    PackUpdateService(store, emptyList(), failing)
                        .apply(
                            instance.id,
                            itemFor(
                                PackFolder.ResourcePacks,
                                old,
                                candidate("FoxPack-2.zip", bytes),
                            ),
                        )
                }
            assertEquals("网络中断", failure.message)
            assertTrue(old.readBytes().contentEquals(before))
            assertNoTemporaryFiles(store, instance, PackFolder.ResourcePacks)
        }

    @Test
    fun `candidate for another game version is rejected before downloading`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            var downloaded = false
            val downloader: PackUpdateDownloader = { _, target, _ ->
                downloaded = true
                target.writeBytes(zipBytes("new"))
            }
            val candidate =
                candidate("FoxPack-2.zip", zipBytes("new"), gameVersions = listOf("1.19.2"))
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PackUpdateService(store, emptyList(), downloader)
                        .apply(instance.id, itemFor(PackFolder.ResourcePacks, old, candidate))
                }
            assertEquals("更新文件不支持当前游戏版本", failure.message)
            assertFalse(downloaded)
            assertTrue(old.exists())
        }

    @Test
    fun `item without a downloadable candidate is rejected`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val bytes = zipBytes("new")
            val item =
                itemFor(PackFolder.ResourcePacks, old, candidate("FoxPack-2.zip", bytes))
                    .copy(status = PackUpdateStatus.Latest, applicable = false)
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PackUpdateService(store, emptyList(), downloader(bytes))
                        .apply(instance.id, item)
                }
            assertEquals("没有可应用的更新", failure.message)
        }

    @Test
    fun `file changed after the check is rejected`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val bytes = zipBytes("new")
            val item = itemFor(PackFolder.ResourcePacks, old, candidate("FoxPack-2.zip", bytes))
            Files.setLastModifiedTime(old, FileTime.fromMillis(item.modified + 2_000L))
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    PackUpdateService(store, emptyList(), downloader(bytes))
                        .apply(instance.id, item)
                }
            assertEquals("文件已改变，请重新检查更新", failure.message)
        }

    @Test
    fun `folder pack is replaced by a zip`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val folder = PackFolder.ShaderPacks
            val old = store.instancePath(instance.id).resolve("shaderpacks/FoxShader Folder")
            old.createDirectories()
            old.resolve("shaders").createDirectories()
            val bytes = zipBytes("new")
            val candidate = candidate("FoxShader Folder.zip", bytes)
            PackUpdateService(store, emptyList(), downloader(bytes))
                .apply(instance.id, itemFor(folder, old, candidate))
            assertFalse(old.exists())
            assertTrue(
                store
                    .instancePath(instance.id)
                    .resolve("shaderpacks/FoxShader Folder.zip")
                    .readBytes()
                    .contentEquals(bytes),
            )
            assertEquals(1, trashed.size)
            assertTrue(trashed.single().isDirectory())
        }

    @Test
    fun `same file name is replaced in place`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val old = packFile(store, instance, PackFolder.ResourcePacks, "FoxPack.zip")
            val bytes = zipBytes("new")
            PackUpdateService(store, emptyList(), downloader(bytes))
                .apply(
                    instance.id,
                    itemFor(
                        PackFolder.ResourcePacks,
                        old,
                        candidate("FoxPack.zip", bytes),
                    ),
                )
            assertTrue(old.readBytes().contentEquals(bytes))
        }

    @Test
    fun `candidate file name cannot escape the pack folder`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val folder = PackFolder.ResourcePacks
            val old = packFile(store, instance, folder, "FoxPack.zip")
            val bytes = zipBytes("new")
            val candidate = candidate("../../evil.zip", bytes)
            PackUpdateService(store, emptyList(), downloader(bytes))
                .apply(instance.id, itemFor(folder, old, candidate))
            val directory = store.instancePath(instance.id).resolve(folder.directory)
            assertTrue(directory.resolve(".._.._evil.zip").exists())
            assertFalse(store.instancePath(instance.id).resolve("evil.zip").exists())
        }

    private fun assertNoTemporaryFiles(
        store: LauncherStore,
        instance: GameInstance,
        folder: PackFolder,
    ) {
        val directory = store.instancePath(instance.id).resolve(folder.directory)
        assertTrue(directory.listDirectoryEntries().none { it.name.startsWith(".molifox-") })
    }

    private fun store(): LauncherStore {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory: Path =
                    temporary.resolve("profile-${UUID.randomUUID()}")

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                    trashed.add(target)
                }
            }
        return LauncherStore(platform)
    }

    private fun instance(store: LauncherStore): GameInstance {
        val instance =
            GameInstance(
                name = "Packs",
                gameVersion = "1.20.1",
                versionId = "fixture",
                loader = LoaderType.Fabric,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun packFile(
        store: LauncherStore,
        instance: GameInstance,
        folder: PackFolder,
        name: String,
    ): Path =
        store.instancePath(instance.id).resolve(folder.directory).resolve(name).also {
            it.parent.createDirectories()
            it.writeBytes(zipBytes("old"))
        }

    private fun itemFor(
        folder: PackFolder,
        old: Path,
        candidate: PackUpdateCandidate,
    ): PackUpdateItem =
        PackUpdateItem(
            filename = old.name,
            folder = folder,
            currentVersion = "1.0",
            status = PackUpdateStatus.Updatable,
            latestVersion = candidate.versionNumber,
            candidate = candidate,
            matchSource = PackMatchSource.Fingerprint,
            applicable = true,
            size = if (old.isDirectory()) 0L else old.fileSize(),
            modified = old.getLastModifiedTime().toMillis(),
        )

    private fun candidate(
        filename: String,
        bytes: ByteArray,
        size: Long = bytes.size.toLong(),
        sha512: String = digest(bytes),
        gameVersions: List<String> = listOf("1.20.1"),
    ): PackUpdateCandidate =
        PackUpdateCandidate(
            projectId = "fox",
            projectTitle = "Fox Pack",
            versionId = "v-1.1",
            versionNumber = "1.1",
            filename = filename,
            downloadUrl = "https://example.invalid/$filename",
            provider = "modrinth",
            gameVersions = gameVersions,
            size = size,
            sha512 = sha512,
            sha1 = "",
        )

    private fun downloader(bytes: ByteArray): PackUpdateDownloader =
        { _, target, _ ->
            target.writeBytes(bytes)
        }

    private fun zipBytes(label: String): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("pack.mcmeta"))
            archive.write("""{"label":"$label"}""".toByteArray())
            archive.closeEntry()
        }
        return output.toByteArray()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-512")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
