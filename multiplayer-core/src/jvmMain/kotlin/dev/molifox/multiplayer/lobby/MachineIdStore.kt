// 稳定机器标识：EasyTier machine-id 与 Scaffolding 玩家 id 共用，首次运行时生成并落盘。
package dev.molifox.multiplayer.lobby

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 读写 `<配置目录>/machine-id`。
 *
 * 文件内容非法或不可读时重新生成；读取异常只在内部回退，不阻塞联机启动。
 */
class MachineIdStore(
    private val file: Path,
) {
    /** 读取或创建机器标识；返回值一定非空。 */
    fun read(): String =
        synchronized(this) {
            readExisting() ?: create()
        }

    private fun readExisting(): String? =
        try {
            if (!Files.isRegularFile(file)) return null
            val value = Files.readString(file).trim()
            value.takeIf { ID_PATTERN.matches(it) }
        } catch (_: Exception) {
            // 读取失败按“尚未生成”处理，后续写入会修复文件。
            null
        }

    private fun create(): String {
        val value = UUID.randomUUID().toString()
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".machine-id-", ".tmp")
        try {
            Files.writeString(temporary, value)
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return value
    }

    private companion object {
        const val UUID_TEXT_LENGTH = 36
        val ID_PATTERN = Regex("^[A-Za-z0-9-]{$UUID_TEXT_LENGTH}$")
    }
}
