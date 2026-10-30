// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证配置存储与后台任务的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoreAndJobsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `current configuration defaults to MoliFox client id and preserves explicit values`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        assertEquals(MoliFoxIdentity.MICROSOFT_CLIENT_ID, store.data.settings.microsoftClientId)
        store.mutate { it.copy(settings = it.settings.copy(microsoftClientId = "")) }
        val migrated = LauncherStore(DesktopPlatform(), directory)
        assertEquals("", migrated.data.settings.microsoftClientId)
        val customId = "00000000-0000-0000-0000-000000000001"
        migrated.mutate { it.copy(settings = it.settings.copy(microsoftClientId = customId)) }
        assertEquals(
            customId,
            LauncherStore(DesktopPlatform(), directory).data.settings.microsoftClientId,
        )
    }

    @Test
    fun `persistent data survives restart and damaged configuration recovers`() {
        val platform = DesktopPlatform()
        val first = LauncherStore(platform, directory)
        val account = Account(name = "MoliFox", uuid = offlineUuid("MoliFox"))
        first.mutate { it.copy(accounts = listOf(account), activeAccountId = account.id) }
        val second = LauncherStore(platform, directory)
        assertEquals(account, second.data.accounts.single())
        second.directories.configuration.writeText("{broken")
        val recovered = LauncherStore(platform, directory)
        assertEquals(account, recovered.data.accounts.single())
        assertTrue(
            second.directories.configurationBackups.listDirectoryEntries().any {
                it.name.startsWith("launcher.damaged-")
            },
        )
    }

    @Test
    fun `queued cancellation does not cancel other downloads`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val jobs = JobQueue(store, scope)
                val gate = CompletableDeferred<Unit>()
                val first = jobs.enqueue("first") { gate.await() }
                val second = jobs.enqueue("second") { error("cancelled work must not execute") }
                delay(100)
                jobs.cancel(second)
                gate.complete(Unit)
                withTimeout(5000) { jobs.awaitIdle() }
                assertEquals(
                    JobStatus.Completed,
                    checkNotNull(
                        store.state.value.jobs
                            .find { it.id == first },
                    ).status,
                )
                assertEquals(
                    JobStatus.Cancelled,
                    checkNotNull(
                        store.state.value.jobs
                            .find { it.id == second },
                    ).status,
                )
                assertFalse(jobs.busy)
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `failed task does not stop the queue`() =
        runBlocking {
            val store = LauncherStore(DesktopPlatform(), directory)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val jobs = JobQueue(store, scope)
                jobs.enqueue("bad") { error("fixture failure") }
                val good = jobs.enqueue("good") { progress(1f, "done") }
                withTimeout(5000) { jobs.awaitIdle() }
                assertEquals(
                    JobStatus.Completed,
                    checkNotNull(
                        store.state.value.jobs
                            .find { it.id == good },
                    ).status,
                )
                assertTrue(
                    store.state.value.jobs
                        .any { it.status == JobStatus.Failed },
                )
            } finally {
                scope.cancel()
            }
        }
}
