// 负责本机 Java 检测和运行时安装。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

fun parseJavaVersion(output: String): Int? =
    Regex("(?:java|openjdk) version \"(?:1\\.)?(\\d+)", RegexOption.IGNORE_CASE)
        .find(output)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: Regex("java\\.version\\s*=\\s*(?:1\\.)?(\\d+)")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

class JavaService(private val store: LauncherStore, private val network: NetworkClient) {
    @Volatile private var cached: List<JavaRuntime> = emptyList()
    private val installLocks = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()

    suspend fun inspect(executable: String): JavaRuntime? =
        withContext(Dispatchers.IO) {
            runCatching {
                val process =
                    ProcessBuilder(executable, "-XshowSettings:properties", "-version")
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
                    val version = parseJavaVersion(output) ?: return@runCatching null
                    val home =
                        Regex("java\\.home\\s*=\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
                    val path =
                        home?.let { Path.of(it, "bin", store.platform.javaExecutable).toString() }
                            ?: executable
                    val vendor =
                        Regex("java\\.vendor\\s*=\\s*(.+)")
                            .find(output)
                            ?.groupValues
                            ?.get(1)
                            ?.trim() ?: "Java"
                    val arch =
                        Regex("os\\.arch\\s*=\\s*(.+)").find(output)?.groupValues?.get(1)?.trim()
                            ?: ""
                    JavaRuntime(path, version, vendor, arch)
                } finally {
                    if (process.isAlive) process.destroyForcibly()
                }
            }
                .getOrNull()
        }

    private fun candidates(root: Path, depth: Int = 0): List<String> {
        if (!root.isDirectory() || Files.isSymbolicLink(root) || depth > 4) return emptyList()
        val java = root.resolve("bin").resolve(store.platform.javaExecutable)
        if (java.isRegularFile()) return listOf(java.toAbsolutePath().toString())
        return runCatching {
                root
                    .listDirectoryEntries()
                    .filter { it.isDirectory() && !Files.isSymbolicLink(it) }
                    .flatMap { candidates(it, depth + 1) }
            }
            .getOrDefault(emptyList())
    }

    suspend fun detect(): List<JavaRuntime> =
        withContext(Dispatchers.IO) {
            val paths =
                mutableSetOf(
                    "java",
                    Path.of(System.getProperty("java.home"), "bin", store.platform.javaExecutable)
                        .toString(),
                )
            System.getenv("JAVA_HOME")?.let {
                paths += Path.of(it, "bin", store.platform.javaExecutable).toString()
            }
            System.getenv("PATH")
                ?.split(java.io.File.pathSeparator)
                ?.filter { it.isNotBlank() }
                ?.forEach { entry ->
                    runCatching { Path.of(entry.trim('"'), store.platform.javaExecutable) }
                        .getOrNull()
                        ?.takeIf { it.isRegularFile() }
                        ?.let { paths += it.toString() }
                }
            if (store.data.settings.javaPath.isNotBlank()) paths += store.data.settings.javaPath
            val roots = mutableListOf(store.dataDirectory.resolve("runtimes"))
            if (store.platform.osName == "windows") {
                val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
                listOf("Java", "Eclipse Adoptium", "Microsoft", "Zulu").forEach {
                    roots.add(Path.of(programFiles, it))
                }
                System.getenv("ProgramFiles(x86)")?.let { roots.add(Path.of(it, "Java")) }
                System.getenv("APPDATA")?.let { roots.add(Path.of(it, ".minecraft/runtime")) }
                windowsJavaHomes().forEach { roots.add(it) }
            } else
                roots.addAll(
                    listOf(
                        Path.of("/usr/lib/jvm"),
                        Path.of("/usr/java"),
                        Path.of(System.getProperty("user.home"), ".jdks"),
                        Path.of(System.getProperty("user.home"), ".minecraft/runtime"),
                    )
                )
            roots.flatMap { candidates(it) }.forEach { paths += it }
            coroutineScope { paths.map { async { inspect(it) } }.awaitAll() }
                .filterNotNull()
                .distinctBy { it.path }
                .sortedByDescending { it.majorVersion }
                .also { cached = it }
        }

    private fun windowsJavaHomes(): List<Path> = runCatching {
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
        val candidates =
            (cached.ifEmpty { detect() })
                .filter {
                    requirement.problem(it, store.platform.architecture) == null &&
                        (!requirement.exactAutomatic || it.majorVersion == requirement.minimum)
                }
                .sortedBy { it.majorVersion }
        for (candidate in candidates) {
            context.checkCancelled()
            val inspected = inspect(candidate.path) ?: continue
            if (requirement.problem(inspected, store.platform.architecture) == null)
                return inspected
        }
        return install(requirement.minimum, context).also {
            check(requirement.problem(it, store.platform.architecture) == null) {
                "下载的 Java 架构或版本不兼容"
            }
        }
    }

    suspend fun install(major: Int, context: JobContext): JavaRuntime =
        installLocks
            .computeIfAbsent(major) { Mutex() }
            .withLock {
                cached.firstOrNull {
                    it.majorVersion == major &&
                        Path.of(it.path)
                            .toAbsolutePath()
                            .normalize()
                            .startsWith(
                                store.dataDirectory.resolve("runtimes").toAbsolutePath().normalize()
                            ) &&
                        Path.of(it.path).isRegularFile()
                } ?: installRuntime(major, context)
            }

    private suspend fun installRuntime(major: Int, context: JobContext): JavaRuntime =
        withContext(Dispatchers.IO) {
            require(major in listOf(8, 16, 17, 21, 25)) { "暂不支持自动安装 Java $major，请手动选择" }
            val os =
                when (store.platform.osName) {
                    "windows" -> "windows"
                    "linux" -> "linux"
                    else -> "mac"
                }
            val arch =
                when (store.platform.architecture) {
                    "arm64" -> "aarch64"
                    "x86" -> "x86"
                    else -> "x64"
                }
            context.progress(0f, "查询 Java $major…")
            val artifact = JavaRuntimePackages(network).find(major, os, arch)
            val isZip = os == "windows"
            val archive =
                store.downloadDirectory.resolve(
                    "java/java-$major-${arch}.${if (isZip) "zip" else "tar.gz"}"
                )
            context.beginTransfers(1)
            network.download(
                DownloadArtifact(
                    artifact.url,
                    archive,
                    artifact.sha256,
                    hashAlgorithm = "SHA-256",
                )
            ) { bytes, total ->
                context.transfer(archive.toString(), bytes, total)
                context.progress(
                    if (total > 0) bytes.toFloat() / total * .85f else 0f,
                    "下载 Java $major · ${bytes / 1024 / 1024} MB",
                )
            }
            val downloadedSize = archive.fileSize()
            context.transfer(archive.toString(), downloadedSize, downloadedSize, completed = true)
            val runtimes = store.dataDirectory.resolve("runtimes").apply { createDirectories() }
            val unpacking =
                runtimes.resolve(".unpacking-${UUID.randomUUID()}").apply { createDirectories() }
            try {
                context.progress(.9f, "解压 Java 运行环境…")
                if (isZip)
                    ZipFile(archive.toFile()).use { zip ->
                        for ((entry, target) in
                            validateArchive(zip, "", unpacking, 1024L * 1024 * 1024)) {
                            coroutineContext.ensureActive()
                            target.parent.createDirectories()
                            zip.getInputStream(entry).use { input ->
                                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    }
                else
                    Files.newInputStream(archive).use { raw ->
                        GzipCompressorInputStream(raw).use { gzip ->
                            TarArchiveInputStream(gzip).use { tar ->
                                var count = 0
                                var totalBytes = 0L
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val entry = tar.nextEntry ?: break
                                    require(++count <= 50000 && entry.size >= 0) { "Java 安装包结构无效" }
                                    totalBytes = Math.addExact(totalBytes, entry.size)
                                    require(totalBytes <= 1024L * 1024 * 1024) { "Java 安装包过大" }
                                    if (!entry.isFile) continue
                                    val relative = entry.name.removePrefix("./")
                                    val target = safePath(unpacking, relative)
                                    target.parent.createDirectories()
                                    Files.copy(tar, target, StandardCopyOption.REPLACE_EXISTING)
                                    runCatching {
                                        Files.setPosixFilePermissions(
                                            target,
                                            PosixFilePermissions.fromString(
                                                if (entry.mode and 0b001001001 != 0) "rwxr-xr-x"
                                                else "rw-r--r--"
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                val candidate = candidates(unpacking).firstOrNull() ?: error("安装包中没有找到 Java 可执行文件")
                val found = inspect(candidate) ?: error("下载的 Java 无法运行")
                require(found.majorVersion == major) { "Java 版本校验失败" }
                require(
                    normalizedJavaArchitecture(found.architecture) ==
                        normalizedJavaArchitecture(store.platform.architecture)
                ) {
                    "Java 架构校验失败"
                }
                val destination =
                    runtimes.resolve(
                        "${artifact.distribution.lowercase()}-$major-$arch-${UUID.randomUUID().toString().take(8)}"
                    )
                val relativeJava = unpacking.relativize(Path.of(found.path))
                Files.move(unpacking, destination)
                val runtime = found.copy(path = destination.resolve(relativeJava).toString())
                synchronized(this@JavaService) { cached = cached + runtime }
                archive.deleteIfExists()
                store.log("${artifact.distribution} Java $major 安装完成")
                runtime
            } finally {
                // Only the newly created, verified temporary folder is cleaned up.
                if (
                    unpacking.exists() &&
                        unpacking.parent.toAbsolutePath().normalize() ==
                            runtimes.toAbsolutePath().normalize() &&
                        unpacking.fileName.toString().startsWith(".unpacking-")
                ) {
                    Files.walk(unpacking).use {
                        it.sorted(Comparator.reverseOrder()).forEach { file ->
                            Files.deleteIfExists(file)
                        }
                    }
                }
            }
        }
}
