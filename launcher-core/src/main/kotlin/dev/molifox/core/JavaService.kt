// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责本机 Java 的发现、探测与选择；安装细节在 JavaRuntimeInstaller。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 从 java -version / -XshowSettings 输出解析主版本（1.8.0_512 → 8，21.0.5 → 21）。 */
fun parseJavaVersion(output: String): Int? = parseJavaProbe(output)?.major

internal data class JavaProbe(
    val major: Int,
    val version: String,
    val vendor: String,
    val architecture: String,
    val bits: Int,
    val home: String?,
)

internal fun parseJavaProbe(output: String): JavaProbe? {
    val versionText =
        Regex("(?:java|openjdk) version \"((?:1\\.)?[0-9][0-9._-]*)\"", RegexOption.IGNORE_CASE)
            .find(output)
            ?.groupValues
            ?.get(1)
            ?: Regex("java\\.version\\s*=\\s*((?:1\\.)?[0-9][0-9._-]*)")
                .find(output)
                ?.groupValues
                ?.get(1)
            ?: return null
    val major = javaMajorOfVersion(versionText) ?: return null
    val vendor =
        Regex("java\\.vendor\\s*=\\s*(.+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Java"
    val architecture =
        Regex("os\\.arch\\s*=\\s*(.+)").find(output)?.groupValues?.get(1)?.trim().orEmpty()
    val bits =
        Regex("sun\\.arch\\.data\\.model\\s*=\\s*(\\d+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: bitsOf(architecture)
    val home =
        Regex("java\\.home\\s*=\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
    return JavaProbe(major, versionText, vendor, architecture, bits, home)
}

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

class JavaService(private val store: LauncherStore, private val network: NetworkClient) {
    private val installLocks = ConcurrentHashMap<Int, Mutex>()
    private val probeLock = Mutex()

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

    private fun runtimeIndex() = JavaRuntimeIndex(runtimesDirectory, store.platform.javaExecutable)

    private fun cacheKey(): String {
        val selected =
            store.data.selectedInstanceId
                ?.let { id -> store.data.instances.find { it.id == id } }
                ?.javaPath
                .orEmpty()
        return (
            listOf(store.data.settings.javaPath, selected, revision.toString()) +
                store.data.settings.customJavaPaths
        )
            .joinToString("|")
    }

    /**
     * 探测单个 Java；返回的 path 统一指向 java.home/bin/java，方便启动时用绝对路径。
     * [verifyRunnable] 为 true 时强制运行 `java -version`（安装/手动选择时用），否则 release
     * 文件已经给出完整版本就跳过子进程。
     */
    suspend fun inspect(
        executable: String,
        verifyRunnable: Boolean = false,
    ): JavaRuntime? = withContext(Dispatchers.IO) {
        val resolved = resolveJavaPath(executable) ?: return@withContext null
        if (!resolved.exists) return@withContext null
        val probe =
            (if (verifyRunnable) runJavaVersionProbe(Path.of(resolved.display))
            else probe(resolved.display)) ?: return@withContext null
        runtime(resolved.display, probe, JavaRuntimeSource.System, null)
    }

    /** 已存在路径的探测：优先读 JDK/JRE 的 release，缺版本或架构信息时再运行 java -version。 */
    private suspend fun probe(executable: String): JavaProbe? = withContext(Dispatchers.IO) {
        val path = runCatching { Path.of(executable) }.getOrNull() ?: return@withContext null
        if (!path.isRegularFile()) return@withContext null
        javaProbeFromRelease(path.parent?.parent ?: return@withContext null)
            ?: runJavaVersionProbe(path)
    }

    private suspend fun runJavaVersionProbe(executable: Path): JavaProbe? =
        withContext(Dispatchers.IO) {
            runCatching {
                    // 用参数列表而不是拼接命令行：路径里的空格、中文和引号都原样传给子进程。
                    val process =
                        ProcessBuilder(
                                executable.toString(),
                                "-XshowSettings:properties",
                                "-version",
                            )
                            .redirectErrorStream(true)
                            .start()
                    try {
                        val output =
                            withTimeout(10_000) {
                                runInterruptible(Dispatchers.IO) {
                                    process.inputStream.bufferedReader().readText()
                                }
                            }
                        check(process.waitFor(2, TimeUnit.SECONDS)) { "Java 检测超时" }
                        parseJavaProbe(output)
                    } finally {
                        if (process.isAlive) process.destroyForcibly()
                    }
                }
                .getOrNull()
        }

    private data class ResolvedJavaPath(val display: String, val key: String, val exists: Boolean)

    /**
     * 归一化可执行文件路径：绝对路径 + toRealPath（解析符号链接、Windows 8.3 短名与大小写），
     * 与 `toRealPath()` 的去重方式一致（解析符号链接、Windows 8.3 短名与大小写，同一安装只算一条）。
     */
    private fun resolveJavaPath(raw: String): ResolvedJavaPath? {
        val text = raw.trim().trim('"')
        if (text.isBlank()) return null
        val absolute =
            runCatching { Path.of(text).toAbsolutePath().normalize() }.getOrNull() ?: return null
        val target = runCatching { absolute.toRealPath() }.getOrNull() ?: absolute
        val exists = runCatching { target.isRegularFile() }.getOrDefault(false)
        val key =
            if (store.platform.osName == "windows") target.toString().lowercase(Locale.ROOT)
            else target.toString()
        return ResolvedJavaPath(target.toString(), key, exists)
    }

    /**
     * 按顺序发现 Java：
     * (a) 选中实例 / 全局显式路径 → custom；
     * (b) MoliFox/runtimes/ 下的已安装运行时（index.json，缺清单时扫描 release）→ bundled；
     * (c) JAVA_HOME 与启动器自身 java.home → system；
     * (d) PATH → system；
     * (e) 常见安装目录（含 Windows 注册表 JavaHome）→ system。
     */
    suspend fun detect(force: Boolean = false): List<JavaRuntime> = withContext(Dispatchers.IO) {
        val key = cacheKey()
        if (!force) cached?.takeIf { key == cachedKey }?.let { return@withContext it }
        probeLock.withLock {
            if (!force) cached?.takeIf { key == cachedKey }?.let { return@withLock it }
            val index = runtimeIndex()
            val discovered = LinkedHashMap<String, Candidate>()
            fun offer(
                path: String,
                source: JavaRuntimeSource,
                entry: JavaRuntimeEntry?,
                group: Int,
                keepInvalid: Boolean = false,
            ) {
                val resolved = resolveJavaPath(path) ?: return
                // 不存在的路径只对用户显式添加的条目保留，避免 PATH 里的死链接堆进列表。
                if (!resolved.exists && !keepInvalid) return
                discovered.putIfAbsent(
                    resolved.key,
                    Candidate(resolved.display, source, entry, group, resolved.exists, keepInvalid),
                )
            }
            // (a) 显式路径；用户添加/选择过的路径即使失效也保留，标为「路径不存在」而不是悄悄消失。
            val selected =
                store.data.selectedInstanceId
                    ?.let { id -> store.data.instances.find { it.id == id } }
            listOfNotNull(
                    selected?.javaPath?.takeIf { it.isNotBlank() },
                    store.data.settings.javaPath.takeIf { it.isNotBlank() },
                )
                .forEach { offer(it, JavaRuntimeSource.Custom, null, 0, keepInvalid = true) }
            // 用户手动添加过的路径：不改变系统环境，只在列表里多一条可选项。
            store.data.settings.customJavaPaths.forEach {
                offer(it, JavaRuntimeSource.Custom, null, 0, keepInvalid = true)
            }
            // (b) 启动器自己的 runtimes/
            index.available().sortedByDescending { it.major }.forEach { entry ->
                index.executable(entry)?.let {
                    offer(it.toString(), JavaRuntimeSource.Bundled, entry, 1)
                }
            }
            // (c) JAVA_HOME 与当前运行的 JVM；JAVA_HOME 可能直接指向 JDK，也可能指向其中的 jre/。
            System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
                javaHomeExecutables(home).forEach {
                    offer(it, JavaRuntimeSource.System, null, 2)
                }
            }
            System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let {
                offer(
                    Path.of(it, "bin", store.platform.javaExecutable).toString(),
                    JavaRuntimeSource.System,
                    null,
                    2,
                )
            }
            // (d) PATH
            System.getenv("PATH")
                ?.split(java.io.File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.forEach { entry ->
                    offer(
                        Path.of(entry.trim('"'), store.platform.javaExecutable).toString(),
                        JavaRuntimeSource.System,
                        null,
                        3,
                    )
                }
            // (e) 常见安装目录
            commonRoots().flatMap { candidates(it) }.forEach {
                offer(it, JavaRuntimeSource.System, null, 4)
            }
            val probed =
                coroutineScope {
                    discovered.values
                        .map { candidate ->
                            async {
                                candidate to
                                    if (candidate.exists) probe(candidate.path) else null
                            }
                        }
                        .awaitAll()
                }
            val result =
                probed
                    .mapNotNull { (candidate, info) ->
                        val runtime =
                            when {
                                info != null ->
                                    runtime(candidate.path, info, candidate.source, candidate.entry)
                                candidate.keepInvalid ->
                                    invalidRuntime(
                                        candidate.path,
                                        candidate.source,
                                        if (candidate.exists) "无效" else "路径不存在",
                                    )
                                else -> null
                            }
                        runtime?.let { candidate.group to it }
                    }
                    .sortedWith(
                        compareBy({ it.first }, { !it.second.usable }, { -it.second.majorVersion })
                    )
                    .map { it.second }
            cached = result
            cachedKey = key
            result
        }
    }

    /** JAVA_HOME 既可能指向 JDK 根目录，也可能指向 JDK 内的 jre/ 子目录；两种都试。 */
    private fun javaHomeExecutables(home: String): List<String> {
        val root = runCatching { Path.of(home.trim('"')) }.getOrNull() ?: return emptyList()
        return listOf(root, root.resolve("jre")).map {
            it.resolve("bin").resolve(store.platform.javaExecutable).toString()
        }
    }

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

    private data class Candidate(
        val path: String,
        val source: JavaRuntimeSource,
        val entry: JavaRuntimeEntry?,
        val group: Int,
        val exists: Boolean,
        val keepInvalid: Boolean,
    )

    private fun runtime(
        executable: String,
        probe: JavaProbe,
        source: JavaRuntimeSource,
        entry: JavaRuntimeEntry?,
    ): JavaRuntime {
        val path =
            probe.home
                ?.let {
                    runCatching {
                            Path.of(it, "bin", store.platform.javaExecutable)
                                .takeIf(Path::isRegularFile)
                                ?.toString()
                        }
                        .getOrNull()
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

    /** 只探测本机，不扫描目录；用于给定路径的快速校验。 */
    suspend fun inspectVersion(executable: String): String? = probe(executable)?.version

    private fun candidates(root: Path, depth: Int = 0): List<String> {
        if (!root.isDirectory() || Files.isSymbolicLink(root) || depth > 4) return emptyList()
        val java = root.resolve("bin").resolve(store.platform.javaExecutable)
        if (java.isRegularFile()) return listOf(java.toAbsolutePath().toString())
        return runCatching {
                root.listDirectoryEntries()
                    .filter { it.isDirectory() && !Files.isSymbolicLink(it) }
                    .flatMap { candidates(it, depth + 1) }
            }
            .getOrDefault(emptyList())
    }

    private fun commonRoots(): List<Path> {
        val roots = mutableListOf<Path>()
        if (store.platform.osName == "windows") {
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft")
                .forEach { roots.add(Path.of(programFiles, it)) }
            System.getenv("ProgramFiles(x86)")?.let { roots.add(Path.of(it, "Java")) }
            System.getenv("APPDATA")?.let { roots.add(Path.of(it, ".minecraft/runtime")) }
            System.getenv("LOCALAPPDATA")?.let {
                roots.add(Path.of(it, "Programs", "Eclipse Adoptium"))
            }
            roots.addAll(windowsJavaHomes())
        } else {
            roots.addAll(
                listOf("/usr/lib/jvm", "/usr/java", "/opt/java", "/opt/jdk", "/opt/zulu")
                    .map(::Path)
            )
            roots.add(Path.of(System.getProperty("user.home"), ".jdks"))
            roots.add(Path.of(System.getProperty("user.home"), ".minecraft/runtime"))
        }
        return roots
    }

    /** 只读注册表里登记的 JavaHome；失败（权限/非 Windows）时返回空，不影响其它来源。 */
    private fun windowsJavaHomes(): List<Path> = runCatching {
        if (store.platform.osName != "windows") return@runCatching emptyList()
        val registry = com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE
        val paths = mutableListOf<Path>()
        for (prefix in listOf("SOFTWARE\\JavaSoft", "SOFTWARE\\WOW6432Node\\JavaSoft")) {
            for (kind in listOf("JDK", "JRE", "Java Development Kit", "Java Runtime Environment")) {
                val key = "$prefix\\$kind"
                if (!com.sun.jna.platform.win32.Advapi32Util.registryKeyExists(registry, key))
                    continue
                for (version in
                    com.sun.jna.platform.win32.Advapi32Util.registryGetKeys(registry, key)) {
                    runCatching {
                            com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                                registry,
                                "$key\\$version",
                                "JavaHome",
                            )
                        }
                        .getOrNull()
                        ?.let { paths.add(Path.of(it)) }
                }
            }
        }
        paths
    }
        .getOrDefault(emptyList())

    suspend fun resolve(requiredMajor: Int, preferred: String, context: JobContext): JavaRuntime =
        resolve(JavaRequirement(requiredMajor, exactAutomatic = true), preferred, context)

    suspend fun resolve(
        profile: ResolvedProfile,
        preferred: String,
        context: JobContext,
    ): JavaRuntime = resolve(JavaRequirement.from(profile), preferred, context)

    private suspend fun resolve(
        requirement: JavaRequirement,
        preferred: String,
        context: JobContext,
    ): JavaRuntime {
        if (preferred.isNotBlank()) {
            val runtime = inspect(preferred) ?: error("指定的 Java 无法运行，请在设置中重新选择")
            require(requirement.problem(runtime, store.platform.architecture) == null) {
                requirement.problem(runtime, store.platform.architecture).orEmpty() +
                    "。请改用自动选择或选择兼容的 Java"
            }
            return runtime
        }
        val candidates = detect().filter { requirement.problem(it, store.platform.architecture) == null }
        val pool = if (requirement.exactAutomatic) candidates.filter { it.majorVersion == requirement.minimum } else candidates
        // 优先精确匹配所需主版本，其次用最低的兼容版本。
        pool.sortedWith(compareBy({ it.majorVersion != requirement.minimum }, { it.majorVersion }))
            .firstOrNull()
            ?.let {
                return it
            }
        return install(requirement.minimum, context).also {
            check(requirement.problem(it, store.platform.architecture) == null) {
                "下载的 Java 架构或版本不兼容"
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
    suspend fun remove(runtime: JavaRuntime): JavaRuntime = withContext(Dispatchers.IO) {
        require(runtime.managed) { "只能移除 MoliFox 自己安装的 Java" }
        val runtimes = runtimesDirectory.toAbsolutePath().normalize()
        val executable = Path.of(runtime.path).toAbsolutePath().normalize()
        val directory = executable.parent?.parent?.toAbsolutePath()?.normalize()
        require(
            directory != null &&
                directory.parent == runtimes &&
                directory != runtimes &&
                !Files.isSymbolicLink(directory)
        ) {
            "拒绝删除 runtimes 之外的目录：$directory"
        }
        val index = runtimeIndex()
        if (directory.exists()) {
            Files.walk(directory).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        index.remove(directory.fileName.toString())
        invalidate()
        store.log("已移除 ${runtime.displayName}：${runtime.path}")
        runtime
    }
}