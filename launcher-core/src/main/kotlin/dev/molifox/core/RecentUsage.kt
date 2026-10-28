package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.*

/** 最近使用记录的两类条目：世界存档（`saves/` 目录名）与多人服务器（地址）。 */
enum class EntryKind {
    World,
    Server,
}

/**
 * 「最近游玩的存档或服务器」的本地时间记录，文件位于启动器数据目录的
 * `cache/recent-usage.json`（路径来自 [LauncherStore.directories]，不写死）。
 *
 * 约定：
 * - 以 (类型, 实例, 键) 为唯一标识，记录最后一次使用时间；再次记录只更新时间并移到最前。
 * - 每个实例最多保留 [MAX_ENTRIES_PER_INSTANCE] 条，超出时丢弃最旧的条目。
 * - 写入先落临时文件再原子改名；文件缺失/损坏按空记录处理，读写失败静默忽略，从不抛异常。
 */
class RecentUsage(
    private val store: LauncherStore,
    private val clock: () -> Instant = Instant::now,
) {
    /** `cache/recent-usage.json`；测试与诊断可直接检查该文件。 */
    val file: Path
        get() = store.directories.cache().root.resolve(FILE_NAME)

    /** 记录一次使用：时间取 [clock]，同一条目去重后移到最前。 */
    @Synchronized
    fun record(kind: EntryKind, instanceId: String, key: String) {
        if (instanceId.isBlank() || key.isBlank()) return
        runCatching {
                val entries = load()
                entries.removeAll {
                    it.kind == kind && it.instanceId == instanceId && it.key == key
                }
                entries.add(0, Usage(kind, instanceId, key, clock()))
                save(cap(entries))
            }
            .onFailure { store.log("记录最近使用失败：${it.message ?: key}", true) }
    }

    /** 某条目最后一次使用时间；没有记录或文件损坏时返回 null。 */
    @Synchronized
    fun lastUsed(kind: EntryKind, instanceId: String, key: String): Instant? =
        runCatching {
                load()
                    .firstOrNull {
                        it.kind == kind && it.instanceId == instanceId && it.key == key
                    }
                    ?.lastUsed
            }
            .getOrNull()

    /** 按最后一次使用时间倒序返回 [kind] 的键，最多 [limit] 条；[limit] 不大于 0 时返回空列表。 */
    @Synchronized
    fun recent(kind: EntryKind, instanceId: String, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        return runCatching {
                load()
                    .filter { it.kind == kind && it.instanceId == instanceId }
                    .sortedByDescending { it.lastUsed }
                    .map { it.key }
                    .distinct()
                    .take(limit)
            }
            .getOrElse { emptyList() }
    }

    /** 删除一条记录；没有记录时不做任何写入。 */
    @Synchronized
    fun forget(kind: EntryKind, instanceId: String, key: String) {
        runCatching {
                val entries = load()
                val remaining =
                    entries.filterNot {
                        it.kind == kind && it.instanceId == instanceId && it.key == key
                    }
                if (remaining.size != entries.size) save(remaining)
            }
            .onFailure { store.log("删除最近使用记录失败：${it.message ?: key}", true) }
    }

    /**
     * 清理已经不存在于磁盘上的记录：删除 [kind] + [instanceId] 下所有不在 [existingKeys] 里的键，
     * 返回实际删掉的条数。
     *
     * 调用方先用当前文件扫描结果算好 [existingKeys]（世界是 `saves/` 里仍有 `level.dat` 的名称，
     * 服务器是 `servers.dat` 里仍有的 `ip`），因此这里只做集合比较：仍在使用的键一次都不动，
     * 也不会和「点击记录」并发写入互相覆盖——[record] 与 [pruneMissing] 都在本对象的锁内完成
     * 读改写。没有过期条目时是纯读，不写文件；写入仍走 [save] 的临时文件 + 原子改名。
     * 读文件或写文件失败只记日志，不抛异常。
     */
    @Synchronized
    fun pruneMissing(kind: EntryKind, instanceId: String, existingKeys: Set<String>): Int {
        if (instanceId.isBlank()) return 0
        return runCatching {
                val entries = load()
                val remaining =
                    entries.filterNot {
                        it.kind == kind && it.instanceId == instanceId && it.key !in existingKeys
                    }
                val removed = entries.size - remaining.size
                if (removed > 0) {
                    save(remaining)
                    store.log("已清理最近使用记录：${kind.name} / $instanceId，共 $removed 条")
                }
                removed
            }
            .getOrElse {
                store.log("清理最近使用记录失败：${it.message ?: kind.name}", true)
                0
            }
    }

    /** 读取文件并按文件内顺序（新到旧）返回；损坏或版本不认识时当作空记录。 */
    private fun load(): MutableList<Usage> {
        if (!file.isRegularFile()) return mutableListOf()
        val root = runCatching { readObject(file) }.getOrNull() ?: return mutableListOf()
        if (root.int("version") != FILE_VERSION) return mutableListOf()
        return root.array("entries")
            .mapNotNull { element ->
                val item = element.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@mapNotNull null
                val kind =
                    runCatching { EntryKind.valueOf(item.string("kind")) }.getOrNull()
                        ?: return@mapNotNull null
                val instanceId = item.string("instanceId")
                val key = item.string("key")
                val lastUsed =
                    runCatching { Instant.parse(item.string("lastUsed")) }.getOrNull()
                        ?: return@mapNotNull null
                if (instanceId.isBlank() || key.isBlank()) null
                else Usage(kind, instanceId, key, lastUsed)
            }
            .toMutableList()
    }

    /** 用 [writeJson] 原子写入（临时文件 + 改名）；数组顺序即新到旧。 */
    private fun save(entries: List<Usage>) {
        val json =
            JsonObject().apply {
                addProperty("version", FILE_VERSION)
                add(
                    "entries",
                    JsonArray().apply {
                        entries.forEach { entry ->
                            add(
                                JsonObject().apply {
                                    addProperty("kind", entry.kind.name)
                                    addProperty("instanceId", entry.instanceId)
                                    addProperty("key", entry.key)
                                    addProperty("lastUsed", entry.lastUsed.toString())
                                }
                            )
                        }
                    },
                )
            }
        writeJson(file, json)
    }

    /** 每个实例只留最新的 [MAX_ENTRIES_PER_INSTANCE] 条；列表已按新到旧排列。 */
    private fun cap(entries: List<Usage>): List<Usage> {
        val perInstance = mutableMapOf<String, Int>()
        return entries.filter { entry ->
            val count = perInstance[entry.instanceId] ?: 0
            if (count >= MAX_ENTRIES_PER_INSTANCE) false
            else {
                perInstance[entry.instanceId] = count + 1
                true
            }
        }
    }

    private data class Usage(
        val kind: EntryKind,
        val instanceId: String,
        val key: String,
        val lastUsed: Instant,
    )

    private companion object {
        const val FILE_NAME = "recent-usage.json"
        const val FILE_VERSION = 1
        const val MAX_ENTRIES_PER_INSTANCE = 100
    }
}