// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责按来源收集本机 Java 候选路径，并归一化可执行文件路径。

package dev.molifox.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

private const val JAVA_PATH_PRIORITY = 3
private const val JAVA_COMMON_ROOT_PRIORITY = 4
private const val MAX_JAVA_SCAN_DEPTH = 4

/** 归一化后的 Java 可执行文件；[key] 用于同一安装去重，[exists] 表示当前是否仍是普通文件。 */
internal data class JavaResolvedPath(
    val display: String,
    val key: String,
    val exists: Boolean,
)

/**
 * 归一化可执行文件路径：绝对路径 + toRealPath（解析符号链接、Windows 8.3 短名与大小写），
 * 与 `toRealPath()` 的去重方式一致（解析符号链接、Windows 8.3 短名与大小写，同一安装只算一条）。
 */
internal fun resolveJavaPath(
    store: LauncherStore,
    raw: String,
): JavaResolvedPath? {
    val text = raw.trim().trim('"')
    if (text.isBlank()) return null
    val absolute =
        runCatching { Path.of(text).toAbsolutePath().normalize() }.getOrNull() ?: return null
    val target = runCatching { absolute.toRealPath() }.getOrNull() ?: absolute
    val exists = runCatching { target.isRegularFile() }.getOrDefault(false)
    val key =
        if (store.platform.osName == "windows") {
            target.toString().lowercase(Locale.ROOT)
        } else {
            target.toString()
        }
    return JavaResolvedPath(target.toString(), key, exists)
}

/** 一个待探测的 Java 候选；[group] 决定排序优先级，[keepInvalid] 表示失效后仍保留在列表里。 */
internal data class JavaCandidate(
    val path: String,
    val source: JavaRuntimeSource,
    val entry: JavaRuntimeEntry?,
    val group: Int,
    val exists: Boolean,
    val keepInvalid: Boolean,
)

/** 候选的来源与分组；[keepInvalid] 为 true 时即使路径失效也保留（用户显式添加过）。 */
private data class JavaCandidateSpec(
    val source: JavaRuntimeSource,
    val entry: JavaRuntimeEntry?,
    val group: Int,
    val keepInvalid: Boolean = false,
)

/** 收集候选路径：显式配置、启动器运行时、JAVA_HOME / 当前 JVM、PATH、常见安装目录与注册表。 */
internal class JavaCandidateCollector(
    private val store: LauncherStore,
) {
    /** 按来源顺序收集，同一归一化路径只保留先出现的条目。 */
    fun collect(): LinkedHashMap<String, JavaCandidate> {
        val discovered = LinkedHashMap<String, JavaCandidate>()
        collectExplicit(discovered)
        collectBundled(discovered)
        collectSystem(discovered)
        return discovered
    }

    private fun runtimeIndex() =
        JavaRuntimeIndex(store.dataDirectory.resolve("runtimes"), store.platform.javaExecutable)

    /** 显式路径；用户添加/选择过的路径即使失效也保留，标为「路径不存在」而不是悄悄消失。 */
    private fun collectExplicit(discovered: MutableMap<String, JavaCandidate>) {
        val selected =
            store.data.selectedInstanceId
                ?.let { id -> store.data.instances.find { it.id == id } }
        listOfNotNull(
            selected?.javaPath?.takeIf { it.isNotBlank() },
            store.data.settings.javaPath
                .takeIf { it.isNotBlank() },
        ).forEach {
            offer(
                discovered,
                it,
                JavaCandidateSpec(
                    JavaRuntimeSource.Custom,
                    null,
                    0,
                    keepInvalid = true,
                ),
            )
        }
        // 用户手动添加过的路径：不改变系统环境，只在列表里多一条可选项。
        store.data.settings.customJavaPaths.forEach {
            offer(
                discovered,
                it,
                JavaCandidateSpec(
                    JavaRuntimeSource.Custom,
                    null,
                    0,
                    keepInvalid = true,
                ),
            )
        }
    }

    private fun collectBundled(discovered: MutableMap<String, JavaCandidate>) {
        val index = runtimeIndex()
        index.available().sortedByDescending { it.major }.forEach { entry ->
            index.executable(entry)?.let {
                offer(
                    discovered,
                    it.toString(),
                    JavaCandidateSpec(JavaRuntimeSource.Bundled, entry, 1),
                )
            }
        }
    }

    private fun collectSystem(discovered: MutableMap<String, JavaCandidate>) {
        // JAVA_HOME 与当前运行的 JVM；JAVA_HOME 可能直接指向 JDK，也可能指向其中的 jre/。
        System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
            javaHomeExecutables(home).forEach {
                offer(discovered, it, JavaCandidateSpec(JavaRuntimeSource.System, null, 2))
            }
        }
        System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let {
            val executable = Path.of(it, "bin", store.platform.javaExecutable).toString()
            offer(discovered, executable, JavaCandidateSpec(JavaRuntimeSource.System, null, 2))
        }
        // PATH
        System
            .getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.forEach { entry ->
                offer(
                    discovered,
                    Path.of(entry.trim('"'), store.platform.javaExecutable).toString(),
                    JavaCandidateSpec(JavaRuntimeSource.System, null, JAVA_PATH_PRIORITY),
                )
            }
        // 常见安装目录
        commonRoots().flatMap { candidates(it) }.forEach {
            offer(
                discovered,
                it,
                JavaCandidateSpec(JavaRuntimeSource.System, null, JAVA_COMMON_ROOT_PRIORITY),
            )
        }
    }

    private fun offer(
        discovered: MutableMap<String, JavaCandidate>,
        path: String,
        spec: JavaCandidateSpec,
    ) {
        val resolved = resolveJavaPath(store, path) ?: return
        // 不存在的路径只对用户显式添加的条目保留，避免 PATH 里的死链接堆进列表。
        if (!resolved.exists && !spec.keepInvalid) return
        discovered.putIfAbsent(
            resolved.key,
            JavaCandidate(
                resolved.display,
                spec.source,
                spec.entry,
                spec.group,
                resolved.exists,
                spec.keepInvalid,
            ),
        )
    }

    /** JAVA_HOME 既可能指向 JDK 根目录，也可能指向 JDK 内的 jre/ 子目录；两种都试。 */
    private fun javaHomeExecutables(home: String): List<String> {
        val root = runCatching { Path.of(home.trim('"')) }.getOrNull() ?: return emptyList()
        return listOf(root, root.resolve("jre")).map {
            it.resolve("bin").resolve(store.platform.javaExecutable).toString()
        }
    }

    private fun candidates(
        root: Path,
        depth: Int = 0,
    ): List<String> {
        val skip =
            !root.isDirectory() || Files.isSymbolicLink(root) || depth > MAX_JAVA_SCAN_DEPTH
        if (skip) return emptyList()
        val java = root.resolve("bin").resolve(store.platform.javaExecutable)
        if (java.isRegularFile()) return listOf(java.toAbsolutePath().toString())
        return runCatching {
            root
                .listDirectoryEntries()
                .filter { it.isDirectory() && !Files.isSymbolicLink(it) }
                .flatMap { candidates(it, depth + 1) }
        }.getOrDefault(emptyList())
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
                    .map { Path.of(it) },
            )
            roots.add(Path.of(System.getProperty("user.home"), ".jdks"))
            roots.add(Path.of(System.getProperty("user.home"), ".minecraft/runtime"))
        }
        return roots
    }

    /** 只读注册表里登记的 JavaHome；失败（权限/非 Windows）时返回空，不影响其它来源。 */
    private fun windowsJavaHomes(): List<Path> =
        runCatching {
            if (store.platform.osName != "windows") return@runCatching emptyList()
            val registry = com.sun.jna.platform.win32.WinReg.HKEY_LOCAL_MACHINE
            val paths = mutableListOf<Path>()
            for (prefix in listOf("SOFTWARE\\JavaSoft", "SOFTWARE\\WOW6432Node\\JavaSoft")) {
                for (kind in listOf(
                    "JDK",
                    "JRE",
                    "Java Development Kit",
                    "Java Runtime Environment",
                )) {
                    val key = "$prefix\\$kind"
                    if (!com.sun.jna.platform.win32.Advapi32Util
                            .registryKeyExists(registry, key)
                    ) {
                        continue
                    }
                    for (
                    version in
                    com.sun.jna.platform.win32.Advapi32Util
                        .registryGetKeys(registry, key)
                    ) {
                        runCatching {
                            com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
                                registry,
                                "$key\\$version",
                                "JavaHome",
                            )
                        }.getOrNull()
                            ?.let { paths.add(Path.of(it)) }
                    }
                }
            }
            paths
        }.getOrDefault(emptyList())
}
