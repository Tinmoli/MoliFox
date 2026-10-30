// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包升级事务的回滚与取消行为。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 事务中途失败与协程取消都必须完整回滚。 */
class PackUpgradeTransactionTest : PackUpgradeTestBase() {
    @Test
    fun `failed upgrade rolls back files, ledger, archive and instance record`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val root = store.instancePath(instance.id)
            val archives = prepareFailedUpgradeFiles(root)
            val staged = PackFileTransaction(store, root)
            try {
                staged.content.resolve("a.txt").writeText("new a")
                staged.content.resolve("c.txt").writeText("new c")
                applyFailingUpgrade(store, instance, root, staged, archives)
            } finally {
                staged.cleanup()
            }
            assertFailedRollback(store, instance, root, archives.first)
        }

    @Test
    fun `cancelled upgrade restores files before any staged file is committed`() =
        runBlocking {
            val store = store()
            val instance = instance(store)
            val root = store.instancePath(instance.id)
            val paths = (0 until 300).map { "mods/f%03d.jar".format(it) }.sorted()
            writeOldCancelFiles(root, paths)
            val archives = prepareCancelArchives()
            val backup = root.resolve(".molifox-pack-upgrade-cancel")
            val staged = PackFileTransaction(store, root)
            try {
                stageCancelFiles(staged, paths)
                val transaction = PackUpgradeTransaction(store, root, backup)
                try {
                    applyCancelledUpgrade(
                        CancelScenario(
                            store,
                            instance,
                            backup,
                            transaction,
                            staged,
                            paths,
                            archives,
                        ),
                    )
                } finally {
                    transaction.cleanup()
                }
            } finally {
                staged.cleanup()
            }
            paths.forEach { path -> assertEquals("old $path", root.resolve(path).readText()) }
            assertEquals("1.0.0", checkNotNull(PackOwnershipStore.read(root, store)).versionId)
            assertEquals("old archive", archives.first.readText())
            assertEquals("", store.instance(instance.id).packFile)
            assertFalse(backup.exists())
        }

    /** 旧文件与 1.0.0 账本，并准备升级目标档案的位置。 */
    private fun prepareFailedUpgradeFiles(root: Path): Pair<Path, Path> {
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
        return prepareCancelArchives()
    }

    private fun fixtureChange(
        path: String,
        action: PackFileAction,
    ) = PackFileChange(path, action, PackUserState.Unchanged, "fixture")

    private fun failedUpgradePlan() =
        upgradePlan(
            listOf(
                fixtureChange("a.txt", PackFileAction.Replace),
                fixtureChange("b.txt", PackFileAction.Remove),
                fixtureChange("c.txt", PackFileAction.Add),
            ),
        )

    private fun rollbackLedger() =
        ledger(
            "a.txt" to "new a".toByteArray(),
            "c.txt" to "new c".toByteArray(),
            versionId = "2.0.0",
        )

    /** 让事务在提交前抛错，并断言错误类型。 */
    private suspend fun expectFailedApplication(
        store: LauncherStore,
        instance: GameInstance,
        transaction: PackUpgradeTransaction,
        staged: PackFileTransaction,
        archives: Pair<Path, Path>,
    ) {
        assertFailsWith<IllegalStateException> {
            transaction.apply(
                failedUpgradePlan(),
                staged.content,
                archives.second,
                archives.first,
                rollbackLedger(),
                instance.id,
                { error("fixture failure") },
                JobContext(store, "fixture"),
            )
        }
    }

    private suspend fun applyFailingUpgrade(
        store: LauncherStore,
        instance: GameInstance,
        root: Path,
        staged: PackFileTransaction,
        archives: Pair<Path, Path>,
    ) {
        val transaction =
            PackUpgradeTransaction(
                store,
                root,
                root.resolve(".molifox-pack-upgrade-fixture"),
            )
        try {
            expectFailedApplication(store, instance, transaction, staged, archives)
        } finally {
            transaction.cleanup()
        }
    }

    private fun assertFailedRollback(
        store: LauncherStore,
        instance: GameInstance,
        root: Path,
        archiveTarget: Path,
    ) {
        assertEquals("old a", root.resolve("a.txt").readText())
        assertEquals("old b", root.resolve("b.txt").readText())
        assertFalse(root.resolve("c.txt").exists())
        assertEquals("1.0.0", checkNotNull(PackOwnershipStore.read(root, store)).versionId)
        assertEquals("old archive", archiveTarget.readText())
        assertEquals("", store.instance(instance.id).packFile)
        assertTrue(root.listDirectoryEntries(".molifox-pack-upgrade-*").isEmpty())
    }

    /** 300 份旧文件与匹配的 1.0.0 账本，用于放大取消窗口。 */
    private fun writeOldCancelFiles(
        root: Path,
        paths: List<String>,
    ) {
        paths.forEach { path ->
            root.resolve(path).apply {
                parent.createDirectories()
                writeText("old $path")
            }
        }
        val oldFiles =
            paths.map { path ->
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
    }

    private fun prepareCancelArchives(): Pair<Path, Path> {
        val archiveTarget =
            temporary.resolve("packs/fixture.zip").apply {
                parent.createDirectories()
                writeText("old archive")
            }
        val archiveSource = temporary.resolve("next.zip").apply { writeText("new archive") }
        return archiveTarget to archiveSource
    }

    private fun stageCancelFiles(
        staged: PackFileTransaction,
        paths: List<String>,
    ) {
        paths.forEach { path ->
            staged.content.resolve(path).apply {
                parent.createDirectories()
                writeText("new $path")
            }
        }
    }

    private fun cancelPlan(paths: List<String>) =
        upgradePlan(
            paths.map {
                PackFileChange(it, PackFileAction.Replace, PackUserState.Unchanged, "fixture")
            },
        )

    private fun cancelledLedger(paths: List<String>) =
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
                    val size = data.size.toLong()
                    PackOwnedFile(path, sha(data), size, false, PackFileOrigin.Override)
                },
        )

    /** 只要第一份备份出现就取消作业，确保回滚发生在提交之前。 */
    private fun startCancelWatcher(
        job: Job,
        firstBackup: Path,
        cancelled: AtomicBoolean,
    ) = Thread {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            if (firstBackup.exists()) {
                job.cancel()
                cancelled.set(true)
                return@Thread
            }
            Thread.yield()
        }
    }.apply {
        isDaemon = true
        start()
    }

    private suspend fun applyCancelledUpgrade(scenario: CancelScenario) {
        val job = Job()
        val context = JobContext(scenario.store, "fixture")
        val cancelled = AtomicBoolean(false)
        val watcher = startCancelWatcher(job, scenario.backup.resolve("files/0"), cancelled)
        assertFailsWith<CancellationException> {
            withContext(job) {
                scenario.transaction.apply(
                    cancelPlan(scenario.paths),
                    scenario.staged.content,
                    scenario.archives.second,
                    scenario.archives.first,
                    cancelledLedger(scenario.paths),
                    scenario.instance.id,
                    { it },
                    context,
                )
            }
        }
        watcher.join(5_000)
        assertTrue(cancelled.get(), "watcher did not cancel the transaction")
    }
}

/** 取消用例的输入集合，避免单函数参数过多。 */
private data class CancelScenario(
    val store: LauncherStore,
    val instance: GameInstance,
    val backup: Path,
    val transaction: PackUpgradeTransaction,
    val staged: PackFileTransaction,
    val paths: List<String>,
    val archives: Pair<Path, Path>,
)
