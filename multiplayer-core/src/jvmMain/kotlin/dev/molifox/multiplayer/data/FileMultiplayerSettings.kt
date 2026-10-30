// 原子保存联机配置，所有磁盘操作均在 IO 调度器执行。
package dev.molifox.multiplayer.data

import com.google.gson.Gson
import dev.molifox.multiplayer.common.MultiplayerSettings
import dev.molifox.multiplayer.common.normalized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 联机配置的本地实现：JSON 文件，读取失败按缺省值处理并提示。 */
class FileMultiplayerSettings(
    private val configuration: Path,
) : MultiplayerSettingsRepository {
    private val gson = Gson()

    override suspend fun load(): MultiplayerSettings =
        withContext(Dispatchers.IO) {
            if (!Files.exists(configuration)) return@withContext MultiplayerSettings()
            require(Files.size(configuration) <= MAX_CONFIG_BYTES) { "联机配置文件过大，请检查文件内容" }
            Files.newBufferedReader(configuration).use { reader ->
                val decoded =
                    gson.fromJson(reader, MultiplayerSettings::class.java)
                        ?: throw IllegalArgumentException("联机配置文件为空，请检查文件内容")
                decoded.normalized()
            }
        }

    override suspend fun save(settings: MultiplayerSettings) =
        withContext(Dispatchers.IO) {
            atomicWrite(configuration, gson.toJson(settings.normalized()))
        }

    private fun atomicWrite(
        path: Path,
        text: String,
    ) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".multiplayer-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        private const val MAX_CONFIG_BYTES = 64 * 1024L
    }
}
