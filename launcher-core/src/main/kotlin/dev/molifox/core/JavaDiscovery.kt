// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 的探测、排序与运行时模型构建；缓存与并发控制留在 JavaService。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isRegularFile

private const val MAX_JAVA_PROBE_CONCURRENCY = 2
private const val MAX_JAVA_PROBE_OUTPUT_BYTES = 64 * 1024
private const val JAVA_PROBE_TIMEOUT_SECONDS = 10L
private const val JAVA_PROBE_STOP_TIMEOUT_SECONDS = 2L

private fun stopJavaVersionProbe(process: Process) {
    val descendants = process.descendants().use { it.toList() }
    descendants.asReversed().forEach { child ->
        if (child.isAlive) child.destroyForcibly()
    }
    if (process.isAlive) process.destroyForcibly()
    check(process.waitFor(JAVA_PROBE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        I18n.t("networkB1.javaDiscovery.probeTimeout")
    }
}

/** 探测候选路径并构建 [JavaRuntime]；结果不缓存，由调用方决定何时重探。 */
internal class JavaDiscovery(
    private val store: LauncherStore,
) {
    private val collector = JavaCandidateCollector(store)
    private val probeSlots = Semaphore(MAX_JAVA_PROBE_CONCURRENCY)

    fun runtimeIndex() = JavaRuntimeIndex(store.dataDirectory.resolve("runtimes"), store.platform.javaExecutable)

    /** 按来源收集候选、并发探测，再按来源/可用性/主版本排序。 */
    suspend fun discover(): List<JavaRuntime> {
        val discovered = collector.collect()
        val probed = probeAll(discovered.values.toList())
        return rank(probed)
    }

    /** [verifyRunnable] 为 true 时强制运行 `java -version`，否则 release 完整时跳过子进程。 */
    suspend fun probeExecutable(
        executable: String,
        verifyRunnable: Boolean,
    ): JavaProbe? = if (verifyRunnable) runJavaVersionProbe(Path.of(executable)) else probe(executable)

    /** 已存在路径的探测：优先读 JDK/JRE 的 release，缺版本或架构信息时再运行 java -version。 */
    private suspend fun probe(executable: String): JavaProbe? =
        withContext(Dispatchers.IO) {
            val path = runCatching { Path.of(executable) }.getOrNull() ?: return@withContext null
            if (!path.isRegularFile()) return@withContext null
            javaProbeFromRelease(path.parent?.parent ?: return@withContext null)
                ?: runJavaVersionProbe(path)
        }

    private suspend fun runJavaVersionProbe(executable: Path): JavaProbe? =
        withContext(Dispatchers.IO) {
            probeSlots.withPermit {
                runCatching {
                    // 用参数列表而不是拼接命令行，保留合法路径中的空格和中文。
                    val process =
                        ProcessBuilder(
                            executable.toString(),
                            "-XshowSettings:properties",
                            "-version",
                        ).redirectErrorStream(true)
                            .start()
                    try {
                        readJavaVersionOutput(process)?.let(::parseJavaProbe)
                    } finally {
                        stopJavaVersionProbe(process)
                    }
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                }.getOrNull()
            }
        }

    private suspend fun readJavaVersionOutput(process: Process): String? =
        coroutineScope {
            val output =
                async(Dispatchers.IO) {
                    process.inputStream.use { it.readNBytes(MAX_JAVA_PROBE_OUTPUT_BYTES + 1) }
                }
            try {
                val exited =
                    runInterruptible(Dispatchers.IO) {
                        process.waitFor(JAVA_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    }
                if (exited) {
                    output.await().takeIf { it.size <= MAX_JAVA_PROBE_OUTPUT_BYTES }?.toString(Charsets.UTF_8)
                } else {
                    null
                }
            } finally {
                // 先终止进程再等待结构化子协程退出，避免读输出的协程等待 EOF 导致超时失效。
                stopJavaVersionProbe(process)
            }
        }

    /** 只探测真实存在的候选；并发探测不改变候选顺序。 */
    private suspend fun probeAll(candidates: List<JavaCandidate>): List<Pair<JavaCandidate, JavaProbe?>> =
        coroutineScope {
            candidates
                .map { candidate ->
                    async {
                        candidate to
                            if (candidate.exists) probe(candidate.path) else null
                    }
                }.awaitAll()
        }

    /** 分组排序：来源优先级，其次可用性，最后主版本从高到低。 */
    private fun rank(probed: List<Pair<JavaCandidate, JavaProbe?>>): List<JavaRuntime> =
        probed
            .mapNotNull { (candidate, info) ->
                val runtime =
                    when {
                        info != null ->
                            runtime(
                                candidate.path,
                                info,
                                candidate.source,
                                candidate.entry,
                            )
                        candidate.keepInvalid ->
                            invalidRuntime(
                                candidate.path,
                                candidate.source,
                                if (candidate.exists) {
                                    I18n.t("networkB1.javaDiscovery.invalidRuntime")
                                } else {
                                    I18n.t("networkB1.javaDiscovery.pathMissing")
                                },
                            )
                        else -> null
                    }
                runtime?.let { candidate.group to it }
            }.sortedWith(
                compareBy(
                    { it.first },
                    { !it.second.usable },
                    { -it.second.majorVersion },
                ),
            ).map { it.second }

    private fun invalidRuntime(
        path: String,
        source: JavaRuntimeSource,
        reason: String,
    ): JavaRuntime =
        JavaRuntime(
            path = path,
            majorVersion = 0,
            vendor = "",
            source = source,
            invalidReason = reason,
        )

    /** 把探测结果落成运行时：优先采用 release 声明的 java.home，保证启动用绝对路径。 */
    fun runtime(
        executable: String,
        probe: JavaProbe,
        source: JavaRuntimeSource,
        entry: JavaRuntimeEntry?,
    ): JavaRuntime {
        val path =
            probe.home
                ?.let {
                    runCatching {
                        Path
                            .of(it, "bin", store.platform.javaExecutable)
                            .takeIf(Path::isRegularFile)
                            ?.toString()
                    }.getOrNull()
                }
                ?: executable
        return JavaRuntime(
            path = path,
            majorVersion = probe.major,
            vendor = entry?.vendor?.takeIf { it.isNotBlank() } ?: probe.vendor,
            architecture = probe.architecture,
            version = probe.version,
            bits = probe.bits,
            source = source,
            imageType = entry?.imageType.orEmpty(),
            installedAt = entry?.installedAt.orEmpty(),
            sizeBytes = entry?.sizeBytes ?: 0,
        )
    }
}
