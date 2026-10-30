// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包账本读写、所有权索引与旧版本迁移基线。

package dev.molifox.core

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 账本持久化与所有权构建。 */
class PackUpgradeLedgerTest : PackUpgradeTestBase() {
    @Test
    fun `ownership ledger round trips and damaged files fall back to no ledger`() {
        val store = store()
        val root = temporary.resolve("instance").apply { createDirectories() }
        val ownership =
            ledger(
                "mods/a.jar" to "a".toByteArray(),
                "config/b.txt" to "b".toByteArray(),
                versionId = "2.0.0",
            )
        assertNull(PackOwnershipStore.read(root, store))
        PackOwnershipStore.write(root, ownership)
        val loaded = PackOwnershipStore.read(root, store)
        assertNotNull(loaded)
        assertEquals("2.0.0", loaded.versionId)
        assertEquals(ownership.files, loaded.files)
        assertTrue(root.resolve(PACK_OWNERSHIP_FILE).isRegularFile())

        root.resolve(PACK_OWNERSHIP_FILE).writeText("{broken")
        assertNull(PackOwnershipStore.read(root, store))
        assertTrue(
            store.state.value.logs
                .last()
                .isError,
        )

        root.resolve(PACK_OWNERSHIP_FILE).writeText("""{"formatVersion":99,"files":[]}""")
        assertNull(PackOwnershipStore.read(root, store))
        assertTrue(
            store.state.value.logs
                .last()
                .isError,
        )
    }

    @Test
    fun `ownership is built from index entries and override layers`() {
        val root = temporary.resolve("instance").apply { createDirectories() }
        val jar = "jar".toByteArray()
        val pack = ownershipPack(ownershipIndex(jar))
        ZipFile(pack.toFile()).use { zip ->
            val manifest = readPackManifest(zip, root, "windows")
            val ownership =
                PackOwnershipStore.buildOwnership(
                    manifest,
                    sha256(pack.readBytes()),
                    emptySet(),
                    zip,
                    root,
                )
            assertDownloadedOwnership(ownership, jar, pack)
            val optional =
                PackOwnershipStore.buildOwnership(
                    manifest,
                    sha256(pack.readBytes()),
                    setOf("mods/optional.jar"),
                    zip,
                    root,
                )
            val optionalFile = optional.files.single { it.path == "mods/optional.jar" }
            assertTrue(optionalFile.optional)
        }
    }

    @Test
    fun `migration baseline uses the old archive and falls back to an empty ledger`() {
        val root = temporary.resolve("instance").apply { createDirectories() }
        val archive = exportedPack(mapOf("config/a.txt" to "a".toByteArray()))
        val manifest =
            ZipFile(archive.toFile()).use { zip -> readPackManifest(zip, root, "windows") }
        val baseline =
            PackOwnershipStore.migrationBaseline(root, archive, manifest, null, "windows")
        assertEquals(listOf("config/a.txt"), baseline.files.map { it.path })
        assertEquals(sha("a".toByteArray()), baseline.files.single().sha1)
        assertEquals("Fixture pack", baseline.name)

        assertTrue(PackOwnershipStore.migrationBaseline(root, null).files.isEmpty())
        assertTrue(
            PackOwnershipStore
                .migrationBaseline(root, temporary.resolve("missing.zip"))
                .files
                .isEmpty(),
        )
        val broken = temporary.resolve("broken.zip").apply { writeText("not a zip") }
        assertTrue(PackOwnershipStore.migrationBaseline(root, broken).files.isEmpty())
    }

    /** 构造带下载条目、可选条目与不支持条目的 Modrinth 索引。 */
    private fun ownershipIndex(jar: ByteArray): JsonObject =
        gson
            .toJsonTree(
                mapOf(
                    "formatVersion" to 1,
                    "game" to "minecraft",
                    "versionId" to "1.2.3",
                    "name" to "Fixture pack",
                    "dependencies" to
                        mapOf("minecraft" to "1.20.1", "fabric-loader" to "0.15.0"),
                    "files" to
                        listOf(
                            fileEntry("mods/a.jar", jar),
                            fileEntry("mods/optional.jar", jar, "optional"),
                            fileEntry("mods/server.jar", jar, "unsupported"),
                        ),
                ),
            ).asJsonObject

    /** 把索引与 overrides / client-overrides 打成 .mrpack。 */
    private fun ownershipPack(index: JsonObject): Path =
        temporary.resolve("fixture.mrpack").apply {
            writeBytes(
                zipBytes(
                    mapOf(
                        "modrinth.index.json" to gson.toJson(index).toByteArray(),
                        "overrides/config/base.txt" to "base".toByteArray(),
                        "overrides/config/only.txt" to "only".toByteArray(),
                        "client-overrides/config/base.txt" to "client".toByteArray(),
                    ),
                ),
            )
        }

    /** 校验所有权：下载条目取索引、override 层以客户端层覆盖服务端层。 */
    private fun assertDownloadedOwnership(
        ownership: PackOwnership,
        jar: ByteArray,
        pack: Path,
    ) {
        assertEquals("modrinth", ownership.source)
        assertEquals("1.2.3", ownership.versionId)
        assertEquals("1.20.1", ownership.gameVersion)
        assertEquals("Fabric", ownership.loader)
        assertEquals("0.15.0", ownership.loaderVersion)
        assertEquals(sha256(pack.readBytes()), ownership.archiveSha256)
        val files = ownership.files.associateBy { it.path }
        assertNull(files["mods/optional.jar"])
        assertNull(files["mods/server.jar"])
        assertEquals(PackFileOrigin.Download, files.getValue("mods/a.jar").origin)
        assertEquals(sha(jar), files.getValue("mods/a.jar").sha1)
        assertEquals(PackFileOrigin.Override, files.getValue("config/base.txt").origin)
        assertEquals(sha("client".toByteArray()), files.getValue("config/base.txt").sha1)
        assertEquals(sha("only".toByteArray()), files.getValue("config/only.txt").sha1)
    }
}
