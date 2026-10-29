// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包升级计划、受保护文件和回滚的行为与边界。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackUpgradeTest {
    @TempDir lateinit var temporary: Path

    private fun hash(data: ByteArray, algorithm: String) =
        MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

    private fun sha(data: ByteArray) = hash(data, "SHA-1")

    private fun sha256(data: ByteArray) = hash(data, "SHA-256")

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    private fun instance(
        store: LauncherStore,
        packFile: String = "",
        gameVersion: String = "1.20.1",
    ): GameInstance {
        val instance =
            GameInstance(
                name = "Fixture instance",
                gameVersion = gameVersion,
                versionId = gameVersion,
                installed = true,
                packFile = packFile,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun ledger(
        vararg files: Pair<String, ByteArray>,
        versionId: String = "1.0.0",
        gameVersion: String = "1.20.1",
        loader: String = "Vanilla",
        loaderVersion: String = "",
    ) =
        PackOwnership(
            source = "archive",
            versionId = versionId,
            name = "Fixture pack",
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            archiveSha256 = sha256("$versionId/$gameVersion/$loader".toByteArray()),
            installedAt = "2026-10-27T00:00:00Z",
            files =
                files
                    .map { (path, data) ->
                        PackOwnedFile(
                            path,
                            sha(data),
                            data.size.toLong(),
                            false,
                            PackFileOrigin.Override,
                        )
                    }
                    .sortedBy { it.path },
        )

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    /** 与 exportInstance 相同的自包含格式：molifox-instance.json + files/。 */
    private fun exportedPack(
        files: Map<String, ByteArray>,
        gameVersion: String = "1.20.1",
        name: String = "Fixture pack",
    ): Path {
        val metadata =
            gson.toJson(
                mapOf(
                    "formatVersion" to 1,
                    "instance" to
                        mapOf(
                            "name" to name,
                            "gameVersion" to gameVersion,
                            "loader" to "Vanilla",
                            "loaderVersion" to "",
                        ),
                )
            )
        return temporary.resolve("pack-${UUID.randomUUID()}.zip").apply {
            writeBytes(
                zipBytes(
                    mapOf("molifox-instance.json" to metadata.toByteArray()) +
                        files.mapKeys { "files/${it.key}" }
                )
            )
        }
    }

    private fun fileEntry(path: String, data: ByteArray, client: String = "required") =
        mapOf(
            "path" to path,
            "hashes" to mapOf("sha1" to sha(data), "sha512" to hash(data, "SHA-512")),
            "fileSize" to data.size,
            "env" to mapOf("client" to client, "server" to "required"),
            "downloads" to listOf("https://cdn.modrinth.com/data/$path"),
        )

    // ---- 规划规则 1–9 ----

    @Test
    fun `missing files are added while deleted ones stay missing unless policy restores them`() {
        val old = ledger("config/deleted.txt" to "old".toByteArray(), versionId = "1.0.0")
        val new =
            ledger(
                "config/deleted.txt" to "new".toByteArray(),
                "config/fresh.txt" to "fresh".toByteArray(),
                versionId = "2.0.0",
            )
        val plan = PackUpgradePlanner.plan("instance", old, new, emptyMap())
        val deleted = plan.changes.single { it.path == "config/deleted.txt" }
        assertEquals(PackFileAction.Keep, deleted.action)
        assertEquals(PackUserState.UserDeleted, deleted.userState)
        val fresh = plan.changes.single { it.path == "config/fresh.txt" }
        assertEquals(PackFileAction.Add, fresh.action)
        assertEquals(PackUserState.Unchanged, fresh.userState)

        val restored =
            PackUpgradePlanner.plan(
                "instance",
                old,
                new,
                emptyMap(),
                PackUpgradePolicy(restoreUserDeleted = true),
            )
        val restore = restored.changes.single { it.path == "config/deleted.txt" }
        assertEquals(PackFileAction.Add, restore.action)
        assertEquals(PackUserState.UserDeleted, restore.userState)
    }

    @Test
    fun `files already matching the new hash are kept`() {
        val data = "same".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/a.jar" to data, versionId = "1.0.0"),
                ledger("mods/a.jar" to data, versionId = "2.0.0"),
                mapOf("mods/a.jar" to PackFileState(true, sha(data))),
            )
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.Unchanged, change.userState)
        assertTrue(plan.protectedPaths.isEmpty())
    }

    @Test
    fun `files still matching the old ledger are replaced`() {
        val old = "old".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/a.jar" to old, versionId = "1.0.0"),
                ledger("mods/a.jar" to "new".toByteArray(), versionId = "2.0.0"),
                mapOf("mods/a.jar" to PackFileState(true, sha(old))),
            )
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Replace, change.action)
        assertEquals(PackUserState.Unchanged, change.userState)
    }

    @Test
    fun `user modified files are protected by default and replaced when protection is off`() {
        val old = "old".toByteArray()
        val edited = "user edited".toByteArray()
        val oldLedger = ledger("mods/a.jar" to old, versionId = "1.0.0")
        val newLedger = ledger("mods/a.jar" to "new".toByteArray(), versionId = "2.0.0")
        val current = mapOf("mods/a.jar" to PackFileState(true, sha(edited)))
        val plan = PackUpgradePlanner.plan("instance", oldLedger, newLedger, current)
        val change = plan.changes.single { it.path == "mods/a.jar" }
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserModified, change.userState)
        assertEquals(listOf("mods/a.jar"), plan.protectedPaths)

        val forced =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                newLedger,
                current,
                PackUpgradePolicy(protectUserModified = false),
            )
        assertEquals(PackFileAction.Replace, forced.changes.single().action)
        assertEquals(PackUserState.UserModified, forced.changes.single().userState)
        assertTrue(forced.protectedPaths.isEmpty())
    }

    @Test
    fun `obsolete files are removed and can be kept by policy`() {
        val old = "old".toByteArray()
        val oldLedger = ledger("mods/old.jar" to old, versionId = "1.0.0")
        val current = mapOf("mods/old.jar" to PackFileState(true, sha(old)))
        val plan =
            PackUpgradePlanner.plan("instance", oldLedger, ledger(versionId = "2.0.0"), current)
        assertEquals(PackFileAction.Remove, plan.changes.single().action)
        assertEquals(PackUserState.Unchanged, plan.changes.single().userState)

        val kept =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                ledger(versionId = "2.0.0"),
                current,
                PackUpgradePolicy(removeObsoleteFiles = false),
            )
        assertEquals(PackFileAction.Keep, kept.changes.single().action)
    }

    @Test
    fun `modified obsolete files are protected but removed when protection is off`() {
        val oldLedger = ledger("mods/old.jar" to "old".toByteArray(), versionId = "1.0.0")
        val current = mapOf("mods/old.jar" to PackFileState(true, sha("user edited".toByteArray())))
        val plan =
            PackUpgradePlanner.plan("instance", oldLedger, ledger(versionId = "2.0.0"), current)
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserModified, change.userState)
        assertEquals(listOf("mods/old.jar"), plan.protectedPaths)

        val forced =
            PackUpgradePlanner.plan(
                "instance",
                oldLedger,
                ledger(versionId = "2.0.0"),
                current,
                PackUpgradePolicy(protectUserModified = false),
            )
        assertEquals(PackFileAction.Remove, forced.changes.single().action)
        assertEquals(PackUserState.UserModified, forced.changes.single().userState)
    }

    @Test
    fun `obsolete files the user already deleted stay deleted`() {
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger("mods/old.jar" to "old".toByteArray(), versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                emptyMap(),
            )
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserDeleted, change.userState)
    }

    @Test
    fun `files neither pack owns are treated as user additions and never deleted`() {
        val data = "user file".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                mapOf("mods/user.jar" to PackFileState(true, sha(data))),
                PackUpgradePolicy(removeObsoleteFiles = true, protectUserModified = false),
            )
        val change = plan.changes.single()
        assertEquals(PackFileAction.Keep, change.action)
        assertEquals(PackUserState.UserAdded, change.userState)
    }

    @Test
    fun `game version or loader changes require a component change`() {
        val same =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0"),
                ledger(versionId = "2.0.0"),
                emptyMap(),
            )
        assertFalse(same.requiresComponentChange)

        val gameVersion =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0", gameVersion = "1.20.1"),
                ledger(versionId = "2.0.0", gameVersion = "1.21"),
                emptyMap(),
            )
        assertTrue(gameVersion.requiresComponentChange)

        val loader =
            PackUpgradePlanner.plan(
                "instance",
                ledger(versionId = "1.0.0", loader = "Vanilla"),
                ledger(versionId = "2.0.0", loader = "Fabric", loaderVersion = "0.15.0"),
                emptyMap(),
            )
        assertTrue(loader.requiresComponentChange)
    }

    @Test
    fun `plan counts every action in a mixed upgrade`() {
        val keep = "keep".toByteArray()
        val replace = "replace".toByteArray()
        val removed = "removed".toByteArray()
        val added = "added".toByteArray()
        val plan =
            PackUpgradePlanner.plan(
                "instance",
                ledger(
                    "keep.txt" to keep,
                    "replace.txt" to replace,
                    "removed.txt" to removed,
                    versionId = "1.0.0",
                ),
                ledger(
                    "keep.txt" to keep,
                    "replace.txt" to "replace-v2".toByteArray(),
                    "added.txt" to added,
                    versionId = "2.0.0",
                ),
                mapOf(
                    "keep.txt" to PackFileState(true, sha(keep)),
                    "replace.txt" to PackFileState(true, sha(replace)),
                    "removed.txt" to PackFileState(true, sha(removed)),
                ),
            )
        assertEquals(1, plan.adds)
        assertEquals(1, plan.replaces)
        assertEquals(1, plan.removes)
        assertEquals(1, plan.keeps)
        assertEquals("1.0.0", plan.fromVersionId)
        assertEquals("2.0.0", plan.toVersionId)
    }

    // ---- 账本 ----

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
        assertTrue(store.state.value.logs.last().isError)

        root.resolve(PACK_OWNERSHIP_FILE).writeText("""{"formatVersion":99,"files":[]}""")
        assertNull(PackOwnershipStore.read(root, store))
        assertTrue(store.state.value.logs.last().isError)
    }

    @Test
    fun `ownership is built from index entries and override layers`() {
        val root = temporary.resolve("instance").apply { createDirectories() }
        val jar = "jar".toByteArray()
        val index =
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
                    )
                )
                .asJsonObject
        val pack =
            temporary.resolve("fixture.mrpack").apply {
                writeBytes(
                    zipBytes(
                        mapOf(
                            "modrinth.index.json" to gson.toJson(index).toByteArray(),
                            "overrides/config/base.txt" to "base".toByteArray(),
                            "overrides/config/only.txt" to "only".toByteArray(),
                            "client-overrides/config/base.txt" to "client".toByteArray(),
                        )
                    )
                )
            }
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
            PackOwnershipStore.migrationBaseline(root, temporary.resolve("missing.zip"))
                .files
                .isEmpty()
        )
        val broken = temporary.resolve("broken.zip").apply { writeText("not a zip") }
        assertTrue(PackOwnershipStore.migrationBaseline(root, broken).files.isEmpty())
    }

    // ---- 服务接线：陈旧预览、组件变化、升级成功 ----

    private fun runWithService(block: suspend (LauncherStore, ContentService) -> Unit) =
        runBlocking {
            NetworkClient().use { network ->
                val store = store()
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                block(store, service)
            }
        }

    @Test
    fun `a stale upgrade preview is rejected before touching files`() =
        runWithService { store, service ->
            val instance = instance(store, exportedPack(emptyMap()).toString())
            val root = store.instancePath(instance.id)
            PackOwnershipStore.write(root, ledger(versionId = "1.0.0"))
            val next = exportedPack(mapOf("config/a.txt" to "a".toByteArray()))
            val error =
                assertFailsWith<IllegalArgumentException> {
                    service.upgradePack(
                        instance.id,
                        next,
                        "0".repeat(64),
                        false,
                        JobContext(store, "fixture"),
                    )
                }
            assertContains(error.message.orEmpty(), "升级计划已过期")
            assertFalse(root.resolve("config/a.txt").exists())
            assertEquals(instance.packFile, store.instance(instance.id).packFile)
        }

    @Test
    fun `component changes need explicit approval`() = runWithService { store, service ->
        val instance = instance(store, exportedPack(emptyMap()).toString())
        val root = store.instancePath(instance.id)
        PackOwnershipStore.write(
            root,
            ledger(versionId = "1.0.0", gameVersion = "1.20.1", loader = "Vanilla"),
        )
        val next = exportedPack(mapOf("config/a.txt" to "a".toByteArray()), gameVersion = "1.21")
        val plan = service.previewPackUpgrade(instance.id, next)
        assertTrue(plan.requiresComponentChange)
        val error =
            assertFailsWith<IllegalArgumentException> {
                service.upgradePack(
                    instance.id,
                    next,
                    plan.archiveSha256,
                    false,
                    JobContext(store, "fixture"),
                )
            }
        assertContains(error.message.orEmpty(), "需先切换组件再升级")
        assertFalse(root.resolve("config/a.txt").exists())

        service.upgradePack(
            instance.id,
            next,
            plan.archiveSha256,
            true,
            JobContext(store, "fixture"),
        )
        assertEquals("a", root.resolve("config/a.txt").readText())
    }

    @Test
    fun `upgrade rewrites pack files, keeps user edits and records the new ledger`() =
        runWithService { store, service ->
            val old = "old config".toByteArray()
            val packKeep = "pack keep".toByteArray()
            val edited = "user edited".toByteArray()
            val removed = "old removed mod".toByteArray()
            val oldPack =
                exportedPack(
                    mapOf(
                        "config/fixture.txt" to old,
                        "config/keep.txt" to packKeep,
                        "mods/removed.jar" to removed,
                    )
                )
            val instance = instance(store, oldPack.toString())
            val root = store.instancePath(instance.id)
            root.resolve("config/fixture.txt").apply {
                parent.createDirectories()
                writeBytes(old)
            }
            root.resolve("config/keep.txt").writeBytes(edited)
            root.resolve("mods/removed.jar").apply {
                parent.createDirectories()
                writeBytes(removed)
            }
            PackOwnershipStore.write(
                root,
                ledger(
                    "config/fixture.txt" to old,
                    "config/keep.txt" to packKeep,
                    "mods/removed.jar" to removed,
                    versionId = "1.0.0",
                ),
            )
            val next =
                exportedPack(
                    mapOf(
                        "config/fixture.txt" to "new config".toByteArray(),
                        "config/keep.txt" to "pack keep v2".toByteArray(),
                        "config/added.txt" to "added".toByteArray(),
                    )
                )
            val plan = service.previewPackUpgrade(instance.id, next)
            assertEquals(
                PackFileAction.Replace,
                plan.changes.single { it.path == "config/fixture.txt" }.action,
            )
            val kept = plan.changes.single { it.path == "config/keep.txt" }
            assertEquals(PackFileAction.Keep, kept.action)
            assertEquals(PackUserState.UserModified, kept.userState)
            assertEquals(listOf("config/keep.txt"), plan.protectedPaths)
            assertEquals(
                PackFileAction.Remove,
                plan.changes.single { it.path == "mods/removed.jar" }.action,
            )
            assertEquals(
                PackFileAction.Add,
                plan.changes.single { it.path == "config/added.txt" }.action,
            )

            val result =
                service.upgradePack(
                    instance.id,
                    next,
                    plan.archiveSha256,
                    false,
                    JobContext(store, "fixture"),
                )
            assertEquals(plan.changes, result.changes)
            assertEquals("new config", root.resolve("config/fixture.txt").readText())
            assertEquals("user edited", root.resolve("config/keep.txt").readText())
            assertFalse(root.resolve("mods/removed.jar").exists())
            assertEquals("added", root.resolve("config/added.txt").readText())

            val updated = store.instance(instance.id)
            assertTrue(updated.installed)
            val saved = Path.of(updated.packFile)
            assertTrue(saved.startsWith(store.directories.packArchives))
            assertEquals(digest(next, "SHA-256"), digest(saved, "SHA-256"))
            val ownership = PackOwnershipStore.read(root, store)
            assertNotNull(ownership)
            assertEquals(digest(next, "SHA-256"), ownership.archiveSha256)
            assertEquals(
                setOf("config/added.txt", "config/fixture.txt", "config/keep.txt"),
                ownership.files.map { it.path }.toSet(),
            )
            assertTrue(root.listDirectoryEntries(".molifox-pack-upgrade-*").isEmpty())
            assertTrue(root.listDirectoryEntries(".molifox-pack-*").none { it.isDirectory() })
            assertEquals("Fixture pack", ownership.name)
        }

    // ---- 事务 ----

    private fun upgradePlan(
        changes: List<PackFileChange>,
        toVersion: String = "2.0.0",
    ) =
        PackUpgradePlan(
            fromVersionId = "1.0.0",
            toVersionId = toVersion,
            requiresComponentChange = false,
            changes = changes,
            archiveSha256 = "f".repeat(64),
        )

    @Test
    fun `failed upgrade rolls back files, ledger, archive and instance record`() = runBlocking {
        val store = store()
        val instance = instance(store)
        val root = store.instancePath(instance.id)
        root.resolve("a.txt").writeText("old a")
        root.resolve("b.txt").writeText("old b")
        PackOwnershipStore.write(
            root,
            ledger(
                "a.txt" to "old a".toByteArray(),
                "b.txt" to "old b".toByteArray(),
                versionId = "1.0.0",
            ),
        )
        val archiveTarget =
            temporary.resolve("packs/fixture.zip").apply {
                parent.createDirectories()
                writeText("old archive")
            }
        val archiveSource = temporary.resolve("next.zip").apply { writeText("new archive") }
        val staged = PackFileTransaction(store, root)
        try {
            staged.content.resolve("a.txt").writeText("new a")
            staged.content.resolve("c.txt").writeText("new c")
            val plan =
                upgradePlan(
                    listOf(
                        PackFileChange(
                            "a.txt",
                            PackFileAction.Replace,
                            PackUserState.Unchanged,
                            "fixture",
                        ),
                        PackFileChange(
                            "b.txt",
                            PackFileAction.Remove,
                            PackUserState.Unchanged,
                            "fixture",
                        ),
                        PackFileChange(
                            "c.txt",
                            PackFileAction.Add,
                            PackUserState.Unchanged,
                            "fixture",
                        ),
                    )
                )
            val transaction =
                PackUpgradeTransaction(
                    store,
                    root,
                    root.resolve(".molifox-pack-upgrade-fixture"),
                )
            try {
                assertFailsWith<IllegalStateException> {
                    transaction.apply(
                        plan,
                        staged.content,
                        archiveSource,
                        archiveTarget,
                        ledger(
                            "a.txt" to "new a".toByteArray(),
                            "c.txt" to "new c".toByteArray(),
                            versionId = "2.0.0",
                        ),
                        instance.id,
                        { error("fixture failure") },
                        JobContext(store, "fixture"),
                    )
                }
            } finally {
                transaction.cleanup()
            }
        } finally {
            staged.cleanup()
        }
        assertEquals("old a", root.resolve("a.txt").readText())
        assertEquals("old b", root.resolve("b.txt").readText())
        assertFalse(root.resolve("c.txt").exists())
        assertEquals("1.0.0", PackOwnershipStore.read(root, store)!!.versionId)
        assertEquals("old archive", archiveTarget.readText())
        assertEquals("", store.instance(instance.id).packFile)
        assertTrue(root.listDirectoryEntries(".molifox-pack-upgrade-*").isEmpty())
    }

    @Test
    fun `cancelled upgrade restores files before any staged file is committed`() = runBlocking {
        val store = store()
        val instance = instance(store)
        val root = store.instancePath(instance.id)
        val count = 300
        val paths = (0 until count).map { "mods/f%03d.jar".format(it) }.sorted()
        paths.forEach { path ->
            root.resolve(path).apply {
                parent.createDirectories()
                writeText("old $path")
            }
        }
        val oldFiles = paths.map { path ->
            val data = "old $path".toByteArray()
            PackOwnedFile(path, sha(data), data.size.toLong(), false, PackFileOrigin.Override)
        }
        PackOwnershipStore.write(
            root,
            PackOwnership(
                source = "archive",
                versionId = "1.0.0",
                name = "Fixture pack",
                gameVersion = "1.20.1",
                loader = "Vanilla",
                archiveSha256 = "e".repeat(64),
                installedAt = "2026-10-27T00:00:00Z",
                files = oldFiles,
            ),
        )
        val archiveTarget =
            temporary.resolve("packs/fixture.zip").apply {
                parent.createDirectories()
                writeText("old archive")
            }
        val archiveSource = temporary.resolve("next.zip").apply { writeText("new archive") }
        val backup = root.resolve(".molifox-pack-upgrade-cancel")
        val staged = PackFileTransaction(store, root)
        try {
            paths.forEach { path ->
                staged.content.resolve(path).apply {
                    parent.createDirectories()
                    writeText("new $path")
                }
            }
            val plan =
                upgradePlan(
                    paths.map {
                        PackFileChange(
                            it,
                            PackFileAction.Replace,
                            PackUserState.Unchanged,
                            "fixture",
                        )
                    }
                )
            val job = Job()
            val context = JobContext(store, "fixture")
            val firstBackup = backup.resolve("files/0")
            val cancelled = AtomicBoolean(false)
            val watcher = Thread {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (System.nanoTime() < deadline) {
                    if (firstBackup.exists()) {
                        job.cancel()
                        cancelled.set(true)
                        return@Thread
                    }
                    Thread.yield()
                }
            }
                .apply {
                    isDaemon = true
                    start()
                }
            val transaction = PackUpgradeTransaction(store, root, backup)
            try {
                assertFailsWith<CancellationException> {
                    withContext(job) {
                        transaction.apply(
                            plan,
                            staged.content,
                            archiveSource,
                            archiveTarget,
                            PackOwnership(
                                source = "archive",
                                versionId = "2.0.0",
                                name = "Fixture pack",
                                gameVersion = "1.20.1",
                                loader = "Vanilla",
                                archiveSha256 = "d".repeat(64),
                                installedAt = "2026-10-27T00:00:00Z",
                                files =
                                    paths.map { path ->
                                        val data = "new $path".toByteArray()
                                        PackOwnedFile(
                                            path,
                                            sha(data),
                                            data.size.toLong(),
                                            false,
                                            PackFileOrigin.Override,
                                        )
                                    },
                            ),
                            instance.id,
                            { it },
                            context,
                        )
                    }
                }
            } finally {
                transaction.cleanup()
            }
            watcher.join(5_000)
            assertTrue(cancelled.get(), "watcher did not cancel the transaction")
        } finally {
            staged.cleanup()
        }
        paths.forEach { path -> assertEquals("old $path", root.resolve(path).readText()) }
        assertEquals("1.0.0", PackOwnershipStore.read(root, store)!!.versionId)
        assertEquals("old archive", archiveTarget.readText())
        assertEquals("", store.instance(instance.id).packFile)
        assertFalse(backup.exists())
    }
}
