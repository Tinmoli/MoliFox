// 解包 EasyTier 的 zip 安装包：只写普通文件，拒绝路径穿越、重复条目与超限内容。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/** 解包限制；默认值按官方 2.6.4 包（约 33 MiB）留出余量，同时挡住压缩炸弹。 */
data class ZipLimits(
    val maxEntries: Int = MAX_ENTRIES,
    val maxFileBytes: Long = MAX_FILE_BYTES,
    val maxTotalBytes: Long = MAX_TOTAL_BYTES,
)

private const val MAX_ENTRIES = 256
private const val MAX_FILE_BYTES = 256L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
private const val MAX_RELATIVE_PATH_LENGTH = 4096

/** 解包结果：写出的文件数与总字节数。 */
data class ZipExtractResult(
    val files: Int,
    val bytes: Long,
)

/**
 * 流式解包 zip。
 *
 * 安全边界：只写普通文件（目录条目跳过），不创建符号链接，所以压缩包里的链接条目最多
 * 只会落成普通文件；越界路径、重复条目与超限内容一律抛出异常，由调用方清理目标目录。
 */
object ZipExtractor {
    private const val BUFFER_BYTES = 64 * 1024
    private const val SEPARATOR = '/'
    private const val SEPARATOR_TEXT = "/"
    private const val WINDOWS_SEPARATOR = '\\'
    private const val PARENT = ".."
    private const val CURRENT = "."
    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
    private val WINDOWS_RESERVED_NAME =
        Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

    /**
     * 把 [archive] 解包到 [destination]。
     *
     * [rootPrefix] 非空时只提取该前缀下的文件并剥离前缀，其余条目忽略；
     * 调用方负责在失败后删除 [destination]。
     */
    suspend fun extract(
        archive: Path,
        destination: Path,
        rootPrefix: String? = null,
        limits: ZipLimits = ZipLimits(),
        includedFiles: Set<String>? = null,
    ): ZipExtractResult {
        Files.createDirectories(destination)
        val root = destination.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(root)) { I18n.t("multiplayerB.zipExtractor.entryOutside", root) }
        val prefix = rootPrefix?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()
        val seen = HashSet<String>()
        var files = 0
        var bytes = 0L
        ZipInputStream(Files.newInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val relative = if (entry.isDirectory) null else relativeName(entry.name, prefix)
                if (relative != null && (includedFiles == null || relative in includedFiles)) {
                    require(seen.add(relative)) { I18n.t("multiplayer.zipExtractor.1", relative) }
                    require(files < limits.maxEntries) { I18n.t("multiplayer.zipExtractor.2", relative) }
                    bytes += copyEntry(input, resolveInside(root, relative), limits.maxFileBytes)
                    require(bytes <= limits.maxTotalBytes) { I18n.t("multiplayer.zipExtractor.3", relative) }
                    files++
                }
            }
        }
        require(files > 0) { I18n.t("multiplayer.zipExtractor.4") }
        return ZipExtractResult(files, bytes)
    }

    /** 归一化条目名并剥离前缀；目录前缀之外返回 null，越界条目抛出异常。 */
    private fun relativeName(
        name: String,
        prefix: List<String>,
    ): String? {
        val normalized = name.replace(WINDOWS_SEPARATOR, SEPARATOR)
        val segments = normalized.split(SEPARATOR).filter { it.isNotEmpty() }
        require(
            normalized.isNotEmpty() &&
                normalized.length < MAX_RELATIVE_PATH_LENGTH &&
                '\u0000' !in normalized &&
                !normalized.startsWith(SEPARATOR),
        ) {
            I18n.t("multiplayer.zipExtractor.5", name)
        }
        require(!DRIVE_PREFIX.containsMatchIn(normalized)) { I18n.t("multiplayer.zipExtractor.5", name) }
        require(segments.none { it == PARENT || it == CURRENT }) {
            I18n.t("multiplayer.zipExtractor.6", name)
        }
        require(
            segments.none { segment ->
                ':' in segment ||
                    segment.endsWith('.') ||
                    segment.endsWith(' ') ||
                    WINDOWS_RESERVED_NAME.matches(segment)
            },
        ) {
            I18n.t("multiplayer.zipExtractor.5", name)
        }
        val insidePrefix = prefix.isEmpty() || segments.take(prefix.size) == prefix
        val relative = segments.drop(prefix.size)
        return if (insidePrefix && relative.isNotEmpty()) {
            relative.joinToString(SEPARATOR_TEXT)
        } else {
            null
        }
    }

    /** 解析条目目标路径，并确认仍在解包根目录之内。 */
    private fun resolveInside(
        root: Path,
        relative: String,
    ): Path {
        val target = root.resolve(relative).normalize()
        require(target.startsWith(root) && target != root) {
            I18n.t("multiplayerB.zipExtractor.entryOutside", relative)
        }
        var current = root
        relative.split(SEPARATOR).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) {
                I18n.t("multiplayerB.zipExtractor.entryOutside", relative)
            }
        }
        return target
    }

    /** 流式写出单个条目，返回实际字节数；超限或取消都会中断。 */
    private suspend fun copyEntry(
        input: InputStream,
        target: Path,
        maxFileBytes: Long,
    ): Long {
        target.parent?.let { Files.createDirectories(it) }
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L
        Files.newOutputStream(target).use { output ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                written += count
                require(written <= maxFileBytes) { I18n.t("multiplayer.zipExtractor.7", target.fileName) }
                output.write(buffer, 0, count)
            }
        }
        return written
    }
}
