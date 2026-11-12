// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责本机 Java 的发现、探测与选择；安装细节在 JavaRuntimeInstaller。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

internal data class JavaProbe(
    val major: Int,
    val version: String,
    val vendor: String,
    val architecture: String,
    val bits: Int,
    val home: String?,
)

internal fun parseJavaProbe(output: String): JavaProbe? {
    val versionText = javaVersionText(output) ?: return null
    val major = javaMajorOfVersion(versionText) ?: return null
    val vendor =
        Regex("java\\.vendor\\s*=\\s*(.+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Java"
    val architecture =
        Regex("os\\.arch\\s*=\\s*(.+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            .orEmpty()
    val bits =
        Regex("sun\\.arch\\.data\\.model\\s*=\\s*(\\d+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: bitsOf(architecture)
    val home =
        Regex("java\\.home\\s*=\\s*(.+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    return JavaProbe(major, versionText, vendor, architecture, bits, home)
}

private fun javaVersionText(output: String): String? =
    Regex("(?:java|openjdk) version \"((?:1\\.)?[0-9][0-9._-]*)\"", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.get(1)
        ?: Regex("java\\.version\\s*=\\s*((?:1\\.)?[0-9][0-9._-]*)")
            .find(output)
            ?.groupValues
            ?.get(1)

/**
 * 从 release 文件读取版本/位数/厂商。优先读 release 而不是启动子进程，省一次 JVM 启动；
 * 版本或 OS_ARCH 缺失时返回 null，调用方回退 `java -version`。
 */
internal fun javaProbeFromRelease(javaHome: Path): JavaProbe? {
    val release = readJavaRelease(javaHome)
    val version = release["JAVA_VERSION"]?.takeIf { it.isNotBlank() } ?: return null
    val major = javaMajorOfVersion(version) ?: return null
    val architecture = release["OS_ARCH"].orEmpty().trim()
    if (architecture.isBlank()) return null
    val bits = bitsOf(architecture)
    if (bits == 0) return null
    val vendor =
        listOf("IMPLEMENTOR", "JAVA_VENDOR", "JAVA_VM_VENDOR")
            .firstNotNullOfOrNull { release[it]?.takeIf(String::isNotBlank) }
            ?: "Java"
    return JavaProbe(major, version, vendor, architecture, bits, javaHome.toString())
}

class JavaService(
    private val store: LauncherStore,
    private val network: NetworkClient,
) {
    private val installLocks = ConcurrentHashMap<Int, Mutex>()
    private val probeLock = Mutex()
    private val discovery = JavaDiscovery(store)

    /** 探测结果按进程缓存；显式路径、选中实例或安装目录变化时自动重探，[invalidate] 立即失效。 */
    @Volatile private var cached: List<JavaRuntime>? = null

    @Volatile private var cachedKey: String = ""

    @Volatile private var revision = 0L

    val runtimesDirectory: Path
        get() = store.dataDirectory.resolve("runtimes")

    fun invalidate() {
        cached = null
        cachedKey = ""
        revision++
    }

    private fun cacheKey(): String {
        val selected =
            store.data.selectedInstanceId
                ?.let { id -> store.data.instances.find { it.id == id } }
                ?.javaPath
                .orEmpty()
        return (
            listOf(store.data.settings.javaPath, selected, revision.toString()) +
                store.data.settings.customJavaPaths
        ).joinToString("|")
    }

    /**
     * 探测单个 Java；返回的 path 统一指向 java.home/bin/java，方便启动时用绝对路径。
     * [verifyRunnable] 为 true 时强制运行 `java -version`（安装/手动选择时用），否则 release
     * 文件已经给出完整版本就跳过子进程。
     */
    suspend fun inspect(
        executable: String,
        verifyRunnable: Boolean = false,
    ): JavaRuntime? =
        withContext(Dispatchers.IO) {
            val resolved = resolveJavaPath(store, executable) ?: return@withContext null
            if (!resolved.exists) return@withContext null
            val probe =
                discovery.probeExecutable(resolved.display, verifyRunnable)
                    ?: return@withContext null
            discovery.runtime(resolved.display, probe, JavaRuntimeSource.System, null)
        }

    /**
     * 按顺序发现 Java：
     * (a) 选中实例 / 全局显式路径 → custom；
     * (b) MoliFox/runtimes/ 下的已安装运行时（index.json，缺清单时扫描 release）→ bundled；
     * (c) JAVA_HOME 与启动器自身 java.home → system；
     * (d) PATH → system；
     * (e) 常见安装目录（含 Windows 注册表 JavaHome）→ system。
     */
    suspend fun detect(force: Boolean = false): List<JavaRuntime> =
        withContext(Dispatchers.IO) {
            val key = cacheKey()
            if (!force) cached?.takeIf { key == cachedKey }?.let { return@withContext it }
            probeLock.withLock {
                if (!force) cached?.takeIf { key == cachedKey }?.let { return@withLock it }
                val result = discovery.discover()
                cached = result
                cachedKey = key
                result
            }
        }

    /** 只探测本机，不扫描目录；用于给定路径的快速校验。 */
    suspend fun inspectVersion(executable: String): String? = discovery.probeExecutable(executable, false)?.version

    suspend fun resolve(
        requiredMajor: Int,
        preferred: String,
        context: JobContext,
    ): JavaRuntime = resolve(JavaRequirement(requiredMajor, exactAutomatic = true), preferred, context, true)

    suspend fun resolve(
        profile: ResolvedProfile,
        preferred: String,
        context: JobContext,
        installMissing: Boolean = true,
    ): JavaRuntime = resolve(JavaRequirement.from(profile), preferred, context, installMissing)

    private suspend fun resolve(
        requirement: JavaRequirement,
        preferred: String,
        context: JobContext,
        installMissing: Boolean,
    ): JavaRuntime {
        if (preferred.isNotBlank()) {
            val runtime =
                inspect(preferred) ?: error(I18n.t("networkB2.javaService.preferredJavaUnusable"))
            require(requirement.problem(runtime, store.platform.architecture) == null) {
                I18n.t(
                    "networkB2.javaService.incompatibleHint",
                    requirement.problem(runtime, store.platform.architecture).orEmpty(),
                )
            }
            return runtime
        }
        val candidates =
            detect().filter {
                requirement.problem(it, store.platform.architecture) ==
                    null
            }
        val pool = requirement.automaticCandidates(candidates)
        // 优先精确匹配所需主版本，其次用最低的兼容版本。
        pool
            .sortedWith(compareBy({ it.majorVersion != requirement.minimum }, { it.majorVersion }))
            .firstOrNull()
            ?.let {
                return it
            }
        if (!installMissing) {
            error(I18n.t("settings.java.prompt.title"))
        }
        return install(requirement.minimum, context).also {
            check(requirement.problem(it, store.platform.architecture) == null) {
                I18n.t("networkB2.javaService.downloadedIncompatible")
            }
        }
    }

    /**
     * 安装（或复用）指定主版本的运行时；多个主版本可以并存。
     * [imageType] 为 jre/jdk 时按用户选择装；为空时 JRE 优先、缺失回退 JDK。
     */
    suspend fun install(
        major: Int,
        context: JobContext,
        imageType: String? = null,
    ): JavaRuntime =
        installLocks
            .computeIfAbsent(major) { Mutex() }
            .withLock {
                detect().firstOrNull {
                    it.majorVersion == major &&
                        it.managed &&
                        Path.of(it.path).isRegularFile() &&
                        (imageType == null || it.imageType.equals(imageType, true))
                } ?: JavaRuntimeInstaller(store, network, this)
                    .install(major, context, imageType)
                    .also { invalidate() }
            }

    /** 移除启动器自己安装的运行时：只删除 runtimes/<目录> 与清单条目，绝不动系统 Java。 */
    suspend fun remove(runtime: JavaRuntime): JavaRuntime =
        withContext(Dispatchers.IO) {
            require(runtime.managed) { I18n.t("networkB2.javaService.onlyManagedRemoval") }
            val runtimes = runtimesDirectory.toAbsolutePath().normalize()
            val executable = Path.of(runtime.path).toAbsolutePath().normalize()
            val directory =
                executable.parent
                    ?.parent
                    ?.toAbsolutePath()
                    ?.normalize()
            require(
                directory != null &&
                    directory.parent == runtimes &&
                    directory != runtimes &&
                    !Files.isSymbolicLink(directory),
            ) {
                I18n.t("networkB2.javaService.outsideRuntimes", directory)
            }
            val index = discovery.runtimeIndex()
            if (directory.exists()) {
                Files.walk(directory).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            index.remove(directory.fileName.toString())
            invalidate()
            store.log(
                I18n.t("networkB2.javaService.runtimeRemoved", runtime.displayName, runtime.path),
            )
            runtime
        }
}
