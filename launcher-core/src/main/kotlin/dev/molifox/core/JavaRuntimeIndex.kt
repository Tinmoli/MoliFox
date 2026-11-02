// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 MoliFox/runtimes/index.json：记录启动器自己安装的 Java，并在清单缺失时扫描 release 文件。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readLines

private const val RUNTIME_64_BITS = 64
private const val RUNTIME_32_BITS = 32

/** 一条已安装的 Java。`path` 相对 runtimes/，便于整个数据目录迁移后继续可用。 */
data class JavaRuntimeEntry(
    val path: String = "",
    val major: Int = 0,
    val bits: Int = 0,
    val vendor: String = "",
    /** jre / jdk；用户看到的标签由此决定。 */
    val imageType: String = "jre",
    val version: String = "",
    val sourceUrl: String = "",
    val sha256: String = "",
    val installedAt: String = "",
    val os: String = "",
    val arch: String = "",
    val sizeBytes: Long = 0,
)

data class JavaRuntimeIndexFile(
    val schemaVersion: Int = 1,
    val runtimes: List<JavaRuntimeEntry> = emptyList(),
)

/**
 * runtimes/ 的清单与目录扫描。只信任 runtimes/ 下一层的目录；index.json 条目必须对应存在的 bin/java，
 * 半成品（.part-*、.unpacking-*）一律忽略。index.json 丢失或损坏时按 release 文件恢复（来源仍记为 bundled）。
 */
internal class JavaRuntimeIndex(
    private val runtimes: Path,
    private val javaExecutableName: String = "java.exe",
) {
    private val executableSuffix = if (javaExecutableName.endsWith(".exe")) ".exe" else ""

    val indexFile: Path
        get() = runtimes.resolve("index.json")

    fun read(): List<JavaRuntimeEntry> {
        if (!indexFile.isRegularFile()) return emptyList()
        return runCatching {
            readObject(indexFile).array("runtimes").mapNotNull { element ->
                runCatching { gson.fromJson(element, JavaRuntimeEntry::class.java) }
                    .getOrNull()
                    ?.takeIf { it.path.isNotBlank() && it.major > 0 }
            }
        }.getOrDefault(emptyList())
    }

    /** 已安装、可执行的条目：清单条目优先补全 release 缺失的版本/位数；目录里有但没登记的按 release 恢复。 */
    fun available(): List<JavaRuntimeEntry> {
        val registered = read().associateBy { it.path }
        val scanned = scan()
        val found = mutableListOf<JavaRuntimeEntry>()
        registered.values.forEach { entry ->
            val directory = resolve(entry.path) ?: return@forEach
            if (executable(directory) == null) return@forEach
            val fallback = scanned.firstOrNull { it.path == entry.path }
            found +=
                entry.copy(
                    version = entry.version.ifBlank { fallback?.version.orEmpty() },
                    vendor = entry.vendor.ifBlank { fallback?.vendor.orEmpty() },
                    bits = entry.bits.takeIf { it > 0 } ?: fallback?.bits ?: 64,
                    arch = entry.arch.ifBlank { fallback?.arch.orEmpty() },
                    sizeBytes = entry.sizeBytes.takeIf { it > 0 } ?: directorySize(directory),
                )
        }
        scanned
            .filter { entry -> registered.keys.none { it == entry.path } }
            .forEach { entry ->
                found +=
                    entry.copy(
                        sizeBytes =
                            resolve(entry.path)?.let(::directorySize) ?: entry.sizeBytes,
                    )
            }
        return found.sortedWith(compareBy({ it.major }, { it.path }))
    }

    /** 扫描 runtimes/ 下一层目录中的 release 文件；没有 release 时从目录名（zulu-<major>-...）读取主版本。 */
    fun scan(): List<JavaRuntimeEntry> {
        if (!runtimes.isDirectory()) return emptyList()
        return runCatching {
            runtimes
                .listDirectoryEntries()
                .filter {
                    it.isDirectory() &&
                        !Files.isSymbolicLink(it) &&
                        !it.fileName.toString().startsWith(".")
                }.mapNotNull { directory ->
                    executable(directory) ?: return@mapNotNull null
                    val release = readJavaRelease(directory)
                    val version = release["JAVA_VERSION"].orEmpty()
                    val major =
                        version.takeIf { it.isNotBlank() }?.let(::javaMajorOfVersion)
                            ?: Regex("-(\\d+)-")
                                .find(directory.fileName.toString())
                                ?.groupValues
                                ?.get(1)
                                ?.toIntOrNull()
                            ?: return@mapNotNull null
                    val arch = release["OS_ARCH"].orEmpty()
                    JavaRuntimeEntry(
                        path = directory.fileName.toString(),
                        major = major,
                        bits = bitsOf(arch).takeIf { it > 0 } ?: 64,
                        vendor = release["IMPLEMENTOR"].orEmpty(),
                        imageType = if (isJdk(directory)) "jdk" else "jre",
                        version = version,
                        sourceUrl = "release-file",
                        installedAt =
                            runCatching {
                                Files.getLastModifiedTime(directory).toInstant().toString()
                            }.getOrDefault(Instant.EPOCH.toString()),
                        os = release["OS_NAME"].orEmpty(),
                        arch = arch,
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun resolve(relative: String): Path? {
        if (relative.isBlank()) return null
        val directory = runCatching { safePath(runtimes, relative) }.getOrNull() ?: return null
        return directory.takeIf { it.isDirectory() && !Files.isSymbolicLink(it) }
    }

    fun executable(entry: JavaRuntimeEntry): Path? = resolve(entry.path)?.let(::executable)

    fun executable(directory: Path): Path? =
        directory.resolve("bin").resolve(javaExecutableName).takeIf { it.isRegularFile() }

    /** 原子写入整个清单；先写临时文件再替换，崩溃时不会留下半个 index.json。 */
    @Synchronized
    fun write(entries: List<JavaRuntimeEntry>) {
        runtimes.createDirectories()
        writeJson(
            indexFile,
            JavaRuntimeIndexFile(
                schemaVersion = 1,
                runtimes = entries.sortedWith(compareBy({ it.major }, { it.path })),
            ),
        )
    }

    @Synchronized
    fun upsert(entry: JavaRuntimeEntry) {
        write(read().filterNot { it.path == entry.path } + entry)
    }

    @Synchronized
    fun remove(relative: String) {
        write(read().filterNot { it.path == relative })
    }

    private fun isJdk(directory: Path): Boolean =
        directory.resolve("bin").resolve("javac$executableSuffix").isRegularFile()

    private fun directorySize(directory: Path): Long =
        runCatching {
            Files.walk(directory).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) }
                    .mapToLong { runCatching { it.fileSize() }.getOrDefault(0) }
                    .sum()
            }
        }.getOrDefault(0)
}

internal fun javaMajorOfVersion(version: String): Int? =
    Regex("^(?:1\\.)?(\\d+)")
        .find(version)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

/**
 * 读取 JRE/JDK 自带的 `release` 文件（KEY="value"）。路径含空格或中文不影响；文件缺失、损坏或没有
 * JAVA_VERSION 时返回空 Map，由调用方回退到 `java -version`。用 release 文件识别安装，比启动
 * `java -version` 快且不受编码影响。
 */
fun readJavaRelease(javaHome: Path): Map<String, String> {
    val file = javaHome.resolve("release")
    if (!file.isRegularFile()) return emptyMap()
    return runCatching {
        file
            .readLines()
            .mapNotNull { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim().trim('"')
                key.takeIf { it.isNotEmpty() }?.let { it to value }
            }.toMap()
    }.getOrDefault(emptyMap())
}

internal fun bitsOf(architecture: String): Int =
    when (architecture.lowercase()) {
        "amd64",
        "x86_64",
        "x64",
        "aarch64",
        "arm64",
        "ppc64le",
        "s390x",
        -> RUNTIME_64_BITS
        "x86",
        "i386",
        "i486",
        "i586",
        "i686",
        "arm",
        "arm32",
        -> RUNTIME_32_BITS
        else -> 0
    }
