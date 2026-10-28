// 负责验证配置存储与后台任务的行为与边界。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StoreAndJobsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `old empty client id adopts MoliFox id without replacing custom applications`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        store.mutate { it.copy(settings = it.settings.copy(microsoftClientId = "")) }
        val migrated = LauncherStore(DesktopPlatform(), directory)
        assertEquals(MoliFoxIdentity.microsoftClientId, migrated.data.settings.microsoftClientId)
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
            }
        )
    }

    @Test
    fun `queued cancellation does not cancel other downloads`() = runBlocking {
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
                store.state.value.jobs.find { it.id == first }!!.status,
            )
            assertEquals(
                JobStatus.Cancelled,
                store.state.value.jobs.find { it.id == second }!!.status,
            )
            assertFalse(jobs.busy)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed task does not stop the queue`() = runBlocking {
        val store = LauncherStore(DesktopPlatform(), directory)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val jobs = JobQueue(store, scope)
            jobs.enqueue("bad") { error("fixture failure") }
            val good = jobs.enqueue("good") { progress(1f, "done") }
            withTimeout(5000) { jobs.awaitIdle() }
            assertEquals(
                JobStatus.Completed,
                store.state.value.jobs.find { it.id == good }!!.status,
            )
            assertTrue(store.state.value.jobs.any { it.status == JobStatus.Failed })
        } finally {
            scope.cancel()
        }
    }
}
