// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责在真实 Java 子进程中校验整合包参数与启动配置的集成行为。

package dev.molifox.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真实 Java 子进程验证整合包参数最终落到命令行：
 * 探针只打印 JVM 属性、游戏参数与工作目录，不启动 Minecraft。
 */
internal class PackJavaArgumentsCheck(
    private val temporary: Path,
) {
    /** 编译探针、装进人造版本目录，再真正启动一次并核对日志。 */
    suspend fun verify(
        store: LauncherStore,
        network: NetworkClient,
        instanceId: String,
    ) {
        compileProbe()
        packageProbe(store, instanceId)
        launchProbe(store, network, instanceId)
    }

    /** 编译只打印参数的控制台探针。 */
    private fun compileProbe() {
        val source = temporary.resolve("PackArgumentsFixture.java")
        source.writeText(
            """public class PackArgumentsFixture {
                public static void main(String[] args) {
                    System.out.println("PACK_JVM=" + System.getProperty("pack.message"));
                    System.out.println("PACK_GLOBAL=" + System.getProperty("global.message"));
                    System.out.println("PACK_ARGS=" + String.join("|", args));
                    System.out.println(
                        "PACK_CWD=" +
                            java.nio.file.Path.of("").toAbsolutePath().normalize());
                } }""",
        )
        assertEquals(
            0,
            ToolProvider
                .getSystemJavaCompiler()
                .run(
                    null,
                    null,
                    null,
                    "--release",
                    "17",
                    "-d",
                    temporary.toString(),
                    source.toString(),
                ),
        )
    }

    /** 把探针打进人造的 fixture 版本目录，替换掉测试实例原来的 profile。 */
    private fun packageProbe(
        store: LauncherStore,
        instanceId: String,
    ) {
        // A controlled probe replaces only the fixture profile; no Minecraft is executed.
        store.updateInstance(instanceId) {
            it.copy(gameVersion = "fixture", versionId = "fixture", externalProfile = true)
        }
        val jar =
            store.minecraftRoot.resolve("versions/fixture/fixture.jar").apply {
                parent.createDirectories()
            }
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            output.putNextEntry(JarEntry("PackArgumentsFixture.class"))
            output.write(temporary.resolve("PackArgumentsFixture.class").readBytes())
            output.closeEntry()
        }
        writeJson(
            jar.resolveSibling("fixture.json"),
            mapOf(
                "id" to "fixture",
                "mainClass" to "PackArgumentsFixture",
                "javaVersion" to mapOf("majorVersion" to Runtime.version().feature()),
                "libraries" to emptyList<String>(),
                "arguments" to mapOf("game" to emptyList<String>()),
            ),
        )
    }

    /** 启动一次并等它跑完，随后核对日志里的参数。 */
    private suspend fun launchProbe(
        store: LauncherStore,
        network: NetworkClient,
        instanceId: String,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val java = JavaService(store, network)
        val accounts = AccountService(store, network, scope)
        accounts.addOffline("PackTester")
        val launcher =
            GameLauncher(store, MinecraftInstaller(store, network, java), java, accounts, scope)
        val jobs = JobQueue(store, scope)
        try {
            val jobId = jobs.enqueue("pack argument fixture") { launcher.launch(instanceId, this) }
            withTimeout(30000) {
                jobs.awaitIdle()
                while (store.state.value.runningInstanceId != null) delay(25)
            }
            val job =
                store.state.value.jobs
                    .first { it.id == jobId }
            assertEquals(JobStatus.Completed, job.status, job.error)
            assertProbeLogs(store, instanceId)
        } finally {
            launcher.stop()
            scope.cancel()
        }
    }

    /** 探针打印的 JVM 参数、游戏参数与工作目录都必须是实例配置生效后的值。 */
    private fun assertProbeLogs(
        store: LauncherStore,
        instanceId: String,
    ) {
        val logs =
            store.state.value.logs
                .map { it.message }
        for (
        line in
        listOf(
            "PACK_JVM=茉狐 value with spaces",
            "PACK_GLOBAL=global",
            "PACK_ARGS=--width|${store.data.settings.width}|" +
                "--height|${store.data.settings.height}|--demo|--server|茉狐 server|" +
                "--port|25565|--user-value|kept",
            "PACK_CWD=${store.instancePath(instanceId).toAbsolutePath().normalize()}",
        )
        ) {
            assertTrue(line in logs, logs.joinToString("\n"))
        }
    }
}
