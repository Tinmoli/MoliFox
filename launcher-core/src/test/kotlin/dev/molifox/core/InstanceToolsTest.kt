// 负责验证实例复制、整理和文件账本校验的行为与边界。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstanceToolsTest {
    @TempDir lateinit var temporary: Path

    private fun sha(data: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(data).joinToString("") { "%02x".format(it) }

    private fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    private fun instance(store: LauncherStore, name: String = "Fixture"): GameInstance {
        val instance =
            GameInstance(
                name = name,
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                managedGameDirectory = true,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    private fun ledger(vararg files: Pair<String, ByteArray>): PackOwnership =
        PackOwnership(
            source = "archive",
            versionId = "1.0.0",
            name = "Fixture pack",
            gameVersion = "1.20.1",
            loader = "Fabric",
            loaderVersion = "0.15.0",
            archiveSha256 = "a".repeat(64),
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

    private fun write(file: Path, text: String) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    private fun packArchive(instance: GameInstance, store: LauncherStore, text: String): Path {
        val archive = temporary.resolve("packs/${instance.id}.zip")
        archive.parent.createDirectories()
        archive.writeText(text)
        store.updateInstance(instance.id) { it.copy(packFile = archive.toString()) }
        return archive
    }

    // ---- 复制实例 ----

    @Test
    fun `copy duplicates game files ledger and pack archive into a fresh instance`() = runBlocking {
        val store = store()
        val tools = InstanceTools(store)
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("config/fixture.toml"), "config")
        write(root.resolve("options.txt"), "options")
        PackOwnershipStore.write(root, ledger("mods/a.jar" to "jar".toByteArray()))
        val archive = packArchive(source, store, "archive-v1")

        val copy = tools.copyInstance(source.id, null, context = JobContext(store, "fixture"))

        assertNotEquals(source.id, copy.id)
        assertEquals("Fixture - 副本", copy.name)
        assertEquals("1.20.1", copy.gameVersion)
        assertEquals(LoaderType.Fabric, copy.loader)
        assertEquals("0.15.0", copy.loaderVersion)
        assertTrue(copy.installed)
        assertEquals(source.isolation, copy.isolation)
        assertNull(copy.lastPlayed)
        assertEquals(0, copy.playTimeSeconds)
        val copyRoot = store.instancePath(copy.id)
        assertEquals("jar", copyRoot.resolve("mods/a.jar").readText())
        assertEquals("config", copyRoot.resolve("config/fixture.toml").readText())
        assertEquals("options", copyRoot.resolve("options.txt").readText())
        val copiedLedger = PackOwnershipStore.read(copyRoot, store)
        assertNotNull(copiedLedger)
        assertEquals(PackOwnershipStore.read(root, store)!!.files, copiedLedger.files)
        val copiedArchive = Path.of(copy.packFile)
        assertNotEquals(archive, copiedArchive)
        assertTrue(copiedArchive.startsWith(store.directories.packArchives))
        assertEquals("archive-v1", copiedArchive.readText())
        assertEquals(2, store.data.instances.size)
    }

    @Test
    fun `copy skips logs crash reports caches and pack upgrade backups`() = runBlocking {
        val store = store()
        val tools = InstanceTools(store)
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("logs/latest.log"), "log")
        write(root.resolve("crash-reports/crash.txt"), "crash")
        write(root.resolve("caches/one.bin"), "cache")
        write(root.resolve(".cache/two.bin"), "cache")
        write(root.resolve(".fabric/remappedJars/a.jar"), "remap")
        write(root.resolve(".molifox-pack-upgrade-fixture/files/0/a.jar"), "backup")

        val copy = tools.copyInstance(source.id, null, context = JobContext(store, "fixture"))

        val copyRoot = store.instancePath(copy.id)
        assertEquals("jar", copyRoot.resolve("mods/a.jar").readText())
        assertFalse(copyRoot.resolve("logs").exists())
        assertFalse(copyRoot.resolve("crash-reports").exists())
        assertFalse(copyRoot.resolve("caches").exists())
        assertFalse(copyRoot.resolve(".cache").exists())
        assertFalse(copyRoot.resolve(".fabric/remappedJars").exists())
        assertTrue(copyRoot.listDirectoryEntries(".molifox-pack-upgrade-*").isEmpty())
    }

    @Test
    fun `copy can omit mods and saves while keeping configuration`() = runBlocking {
        val store = store()
        val tools = InstanceTools(store)
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "jar")
        write(root.resolve("saves/world/level.dat"), "level")
        write(root.resolve("config/fixture.toml"), "config")

        val copy =
            tools.copyInstance(
                source.id,
                "只带配置",
                copySaves = false,
                copyMods = false,
                context = JobContext(store, "fixture"),
            )

        assertEquals("只带配置", copy.name)
        val copyRoot = store.instancePath(copy.id)
        assertFalse(copyRoot.resolve("mods").exists())
        assertFalse(copyRoot.resolve("saves").exists())
        assertEquals("config", copyRoot.resolve("config/fixture.toml").readText())
    }

    @Test
    fun `copy adds a sequence number when the duplicate name is taken`() = runBlocking {
        val store = store()
        val tools = InstanceTools(store)
        val source = instance(store)
        write(store.instancePath(source.id).resolve("config/a.txt"), "a")

        val first = tools.copyInstance(source.id, null, context = JobContext(store, "fixture"))
        val second = tools.copyInstance(source.id, null, context = JobContext(store, "fixture"))

        assertEquals("Fixture - 副本", first.name)
        assertEquals("Fixture - 副本 2", second.name)
        assertEquals(3, store.data.instances.size)
    }

    @Test
    fun `cancelled copy removes the partial directory and the instance record`() = runBlocking {
        val store = store()
        val tools = InstanceTools(store)
        val source = instance(store)
        val root = store.instancePath(source.id)
        val payload = ByteArray(64 * 1024) { it.toByte() }
        repeat(200) { index ->
            val file = root.resolve("mods/f%03d.jar".format(index))
            file.parent.createDirectories()
            file.writeBytes(payload)
        }
        val instancesRoot = Path.of(store.data.settings.gameRoot).resolve("instances")
        val job = Job()
        val cancelled = AtomicBoolean(false)
        val watcher = Thread {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                val fresh = runCatching {
                    instancesRoot.listDirectoryEntries().any {
                        it.name != source.id
                    }
                }
                    .getOrDefault(false)
                if (fresh) {
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
        try {
            assertFailsWith<CancellationException> {
                withContext(job) {
                    tools.copyInstance(source.id, null, context = JobContext(store, "fixture"))
                }
            }
        } finally {
            watcher.join(5_000)
        }
        assertTrue(cancelled.get(), "watcher did not cancel the copy")
        assertEquals(1, store.data.instances.size)
        assertEquals(source.id, store.data.instances.single().id)
        assertEquals(listOf(source.id), instancesRoot.listDirectoryEntries().map { it.name })
    }

    // ---- 整理实例目录 ----

    @Test
    fun `tidy reports duplicate mods without deleting them`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "same")
        write(root.resolve("mods/copy-of-a.jar"), "same")
        write(root.resolve("mods/b.jar"), "other")

        val report = InstanceTools(store).tidyInstance(source.id)

        assertEquals(listOf(listOf("a.jar", "copy-of-a.jar")), report.duplicateMods)
        assertEquals(4L, report.totalBytes)
        assertTrue(root.resolve("mods/a.jar").isRegularFile())
        assertTrue(root.resolve("mods/copy-of-a.jar").isRegularFile())
    }

    @Test
    fun `tidy removes empty directories and keeps ones holding files`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        root.resolve("empty/deep").createDirectories()
        write(root.resolve("config/keep/a.txt"), "a")

        val tools = InstanceTools(store)
        val report = tools.tidyInstance(source.id)

        assertEquals(2, report.removedEmptyDirs)
        assertFalse(root.resolve("empty").exists())
        assertTrue(root.resolve("config/keep/a.txt").isRegularFile())
        assertTrue(root.isDirectory())

        assertEquals(0, tools.tidyInstance(source.id, removeEmptyDirs = false).removedEmptyDirs)
    }

    @Test
    fun `tidy counts disabled mods and reports orphan files`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "a")
        write(root.resolve("mods/old.jar.disabled"), "disabled")
        write(root.resolve("mods/broken.jar.part"), "partial")

        val report = InstanceTools(store).tidyInstance(source.id)

        assertEquals(1, report.movedDisabledMods)
        assertEquals(listOf("mods/broken.jar.part"), report.orphanFiles)
        assertTrue(root.resolve("mods/old.jar.disabled").isRegularFile())
        assertTrue(root.resolve("mods/broken.jar.part").isRegularFile())
    }

    @Test
    fun `tidy does not follow directory links outside the instance root`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        val outside = temporary.resolve("outside").apply { createDirectories() }
        outside.resolve("nested").createDirectories()
        outside.resolve("keep.txt").writeText("outside")
        val link = root.resolve("linked")
        createTestDirectoryLink(link, outside)

        val report = InstanceTools(store).tidyInstance(source.id)

        assertEquals(0, report.removedEmptyDirs)
        assertTrue(outside.resolve("nested").exists(), "整理不得越过实例根")
        assertTrue(outside.resolve("keep.txt").isRegularFile())
        assertTrue(link.exists(LinkOption.NOFOLLOW_LINKS), "符号链接本身保留")
    }

    @Test
    fun `tidy keeps directories outside the instance root behind a junction`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        val outside = temporary.resolve("outside").apply { createDirectories() }
        outside.resolve("nested").createDirectories()
        outside.resolve("keep.txt").writeText("outside")
        val link = root.resolve("linked")
        val created = runCatching {
            val process =
                ProcessBuilder(
                        "cmd",
                        "/c",
                        "mklink",
                        "/J",
                        link.toString(),
                        outside.toString(),
                    )
                    .redirectErrorStream(true)
                    .start()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        }
            .getOrDefault(false)
        assumeTrue(created, "当前环境不允许创建目录联接")

        val report = InstanceTools(store).tidyInstance(source.id)

        assertEquals(0, report.removedEmptyDirs)
        assertTrue(outside.resolve("nested").exists(), "整理不得越过实例根")
        assertTrue(outside.resolve("keep.txt").isRegularFile())
    }

    // ---- 一致性校验 ----

    @Test
    fun `verify reports ledger files missing from disk`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "a")
        write(root.resolve("mods/b.jar"), "b")
        PackOwnershipStore.write(
            root,
            ledger(
                "mods/a.jar" to "a".toByteArray(),
                "mods/b.jar" to "b".toByteArray(),
            ),
        )
        root.resolve("mods/b.jar").deleteExisting()

        val report = InstanceTools(store).verifyInstance(source.id)

        assertTrue(report.hasLedger)
        assertEquals(listOf("mods/b.jar"), report.missingCoreFiles)
        assertTrue(report.ledgerMismatches.isEmpty())
        assertTrue(report.unknownFiles.isEmpty())
    }

    @Test
    fun `verify reports ledger hash mismatches`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "edited")
        PackOwnershipStore.write(root, ledger("mods/a.jar" to "a".toByteArray()))

        val report = InstanceTools(store).verifyInstance(source.id)

        assertTrue(report.hasLedger)
        assertEquals(listOf("mods/a.jar"), report.ledgerMismatches)
        assertTrue(report.missingCoreFiles.isEmpty())
        assertTrue(report.unknownFiles.isEmpty())
    }

    @Test
    fun `verify reports unknown files but ignores logs caches and saves`() {
        val store = store()
        val source = instance(store)
        val root = store.instancePath(source.id)
        write(root.resolve("mods/a.jar"), "a")
        write(root.resolve("config/user.toml"), "user")
        write(root.resolve("mods/user.jar"), "user mod")
        write(root.resolve("logs/latest.log"), "log")
        write(root.resolve("caches/one.bin"), "cache")
        write(root.resolve("saves/world/level.dat"), "level")
        PackOwnershipStore.write(root, ledger("mods/a.jar" to "a".toByteArray()))

        val report = InstanceTools(store).verifyInstance(source.id)

        assertEquals(listOf("config/user.toml", "mods/user.jar"), report.unknownFiles)
        assertTrue(report.missingCoreFiles.isEmpty())
        assertTrue(report.ledgerMismatches.isEmpty())
    }

    @Test
    fun `verify returns an empty report for instances without a ledger`() {
        val store = store()
        val source = instance(store)
        write(store.instancePath(source.id).resolve("mods/a.jar"), "a")

        val report = InstanceTools(store).verifyInstance(source.id)

        assertFalse(report.hasLedger)
        assertTrue(report.missingCoreFiles.isEmpty())
        assertTrue(report.ledgerMismatches.isEmpty())
        assertTrue(report.unknownFiles.isEmpty())
    }
}
