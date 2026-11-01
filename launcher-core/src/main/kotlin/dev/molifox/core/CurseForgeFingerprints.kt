// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责本地文件的 CurseForge 指纹匹配与版本记录补齐。

package dev.molifox.core

import com.google.gson.JsonObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime

/** 指纹请求一次最多携带的本地指纹数量。 */
private const val FINGERPRINT_BATCH_SIZE = 1000

internal class CurseForgeFingerprints(
    private val api: CurseForgeApi,
    private val fileRecords: CurseForgeFiles,
) {
    /** 本地文件的 CurseForge 指纹；按路径 + 大小 + 修改时间缓存，重复检查不再重读整颗 jar。 */
    private data class MurmurEntry(
        val size: Long,
        val modified: Long,
        val fingerprint: Long,
    )

    private val murmurCache =
        object : LinkedHashMap<String, MurmurEntry>(64, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MurmurEntry>?) = size > 1024
        }

    private suspend fun fingerprintOf(path: Path): Pair<Long, Long>? {
        val stat =
            runCatching { path.fileSize() to path.getLastModifiedTime().toMillis() }.getOrNull()
        if (stat == null) return null
        val (size, modified) = stat
        val key = path.toAbsolutePath().normalize().toString()
        val cached =
            synchronized(murmurCache) {
                murmurCache[key]?.takeIf { it.size == size && it.modified == modified }
            }
        val fingerprint =
            cached?.fingerprint ?: runCatching { curseForgeFingerprint(path) }.getOrNull()
        if (cached == null && fingerprint != null) {
            synchronized(murmurCache) {
                murmurCache[key] = MurmurEntry(size, modified, fingerprint)
            }
        }
        return fingerprint?.let { it to size }
    }

    /** 本地文件 → CurseForge 指纹记录；请求数与文件数无关。 */
    suspend fun fingerprints(files: List<Path>): Map<Path, JsonObject> = matchFiles(files)

    /**
     * 本地文件 → 规范化后的 CurseForge 版本记录（已合并项目信息）。
     *
     * 用于模组、资源包与光影的更新检查：指纹与项目各一次批量请求，指纹记录字段不全时再用
     * 一次批量文件请求补齐；[kind] 非空时用镜像自己的分类表过滤资源类型。
     */
    suspend fun fingerprintFiles(
        files: List<Path>,
        kind: String? = null,
    ): Map<Path, JsonObject> {
        val matches = matchFiles(files)
        if (matches.isEmpty()) return emptyMap()
        val projects = loadProjects(matches)
        val kinds = if (kind == null) null else api.classIndex().kinds
        val result = mutableMapOf<Path, JsonObject>()
        val incomplete = mutableListOf<Pair<Path, String>>()
        for ((path, file) in matches) {
            val project = projects[file.string("modId")]
            if (
                project == null ||
                (kinds != null && kinds[project.intOrNull("classId") ?: 0] != kind)
            ) {
                continue
            }
            val normalized = runCatching { fileRecords.normalize(file, project) }.getOrNull()
            if (normalized != null) {
                result[path] = normalized
            } else {
                incomplete += path to file.string("id")
            }
        }
        applyFullFiles(incomplete, projects, result)
        return result
    }

    /** 指纹记录字段不全时，用一次批量文件请求补齐并再次规范化。 */
    private suspend fun applyFullFiles(
        incomplete: List<Pair<Path, String>>,
        projects: Map<String, JsonObject>,
        result: MutableMap<Path, JsonObject>,
    ) {
        if (incomplete.isEmpty()) return
        val full = filesById(incomplete.map { it.second })
        for ((path, id) in incomplete) {
            val file = full[id]
            val project = file?.let { projects[it.string("modId")] }
            val normalized =
                if (file != null && project != null) {
                    runCatching { fileRecords.normalize(file, project) }.getOrNull()
                } else {
                    null
                }
            if (normalized != null) result[path] = normalized
        }
    }

    /**
     * 批量指纹匹配：一次 `POST /fingerprints/432`，返回每个本地文件命中的指纹记录。
     *
     * 本地 Murmur 指纹按路径 + 大小 + 修改时间缓存，SHA-1 每个文件只算一次
     * （原先每一对候选都会把整颗 jar 重算一遍）。
     */
    private suspend fun matchFiles(paths: List<Path>): Map<Path, JsonObject> {
        if (paths.isEmpty()) return emptyMap()
        val local = statFiles(paths)
        if (local.isEmpty()) return emptyMap()
        return uniqueMatches(local, exactMatches(local))
    }

    /** 读取路径、大小与修改时间；读不到的文件直接跳过。 */
    private suspend fun statFiles(paths: List<Path>): LinkedHashMap<Path, Pair<Long, Long>> {
        val local = LinkedHashMap<Path, Pair<Long, Long>>()
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            fingerprintOf(path)?.let { local[path] = it }
        }
        return local
    }

    private suspend fun exactMatches(
        local: Map<Path, Pair<Long, Long>>,
    ): MutableMap<Pair<Long, Long>, MutableList<JsonObject>> {
        val exact = mutableMapOf<Pair<Long, Long>, MutableList<JsonObject>>()
        for (batch in local.values
            .map { it.first }
            .distinct()
            .chunked(FINGERPRINT_BATCH_SIZE)) {
            val body = JsonObject().apply { add("fingerprints", gson.toJsonTree(batch)) }
            // 服务端出错时 data 可能是数组或字符串；只有对象才带 exactMatches，其余按未收录处理。
            val data =
                api
                    .request("fingerprints/432", body)
                    .get("data")
                    ?.takeIf { it.isJsonObject }
                    ?.asJsonObject
                    ?: continue
            val records = data.array("exactMatches")
            for (element in records) {
                val file = runCatching { element.asJsonObject.obj("file") }.getOrNull()
                val fingerprint =
                    file?.get("fileFingerprint")?.takeUnless { it.isJsonNull }?.asLong
                val length = file?.get("fileLength")?.takeUnless { it.isJsonNull }?.asLong
                if (file != null && fingerprint != null && length != null) {
                    exact.getOrPut(fingerprint to length) { mutableListOf() } += file
                }
            }
        }
        return exact
    }

    // 同一指纹可能对应多条记录：按 SHA-1 精确校验，只有唯一文件才算确定匹配。
    private suspend fun uniqueMatches(
        local: Map<Path, Pair<Long, Long>>,
        exact: Map<Pair<Long, Long>, MutableList<JsonObject>>,
    ): Map<Path, JsonObject> {
        val matched = mutableMapOf<Path, JsonObject>()
        for ((path, key) in local) {
            currentCoroutineContext().ensureActive()
            val candidates = exact[key]
            // 没有候选记录时不必计算 SHA-1，避免多余的文件读取。
            val sha1 =
                if (candidates == null) {
                    ""
                } else {
                    runCatching { digest(path, "SHA-1") }.getOrDefault("")
                }
            if (candidates == null || sha1.isBlank()) continue
            val unique =
                candidates
                    .filter { file ->
                        file.array("hashes").any {
                            val hash = runCatching { it.asJsonObject }.getOrNull()
                            hash != null &&
                                hash.int("algo") == 1 &&
                                hash.string("value").equals(sha1, true)
                        }
                    }.distinctBy { it.string("id") }
            if (unique.size == 1 && unique.first().string("id").isNotBlank()) {
                matched[path] = unique.first()
            }
        }
        return matched
    }

    /** 批量文件记录：`POST /mods/files`，只在指纹记录字段不全时补一次。 */
    private suspend fun filesById(ids: List<String>): Map<String, JsonObject> {
        val result = mutableMapOf<String, JsonObject>()
        for (batch in ids.distinct().chunked(CURSE_FORGE_BATCH_SIZE)) {
            val body =
                JsonObject().apply {
                    add("fileIds", gson.toJsonTree(batch.mapNotNull { it.toLongOrNull() }))
                }
            for (entry in api.request("mods/files", body).array("data")) {
                val info = runCatching { entry.asJsonObject }.getOrNull() ?: continue
                result[info.string("id")] = info
            }
        }
        return result
    }

    private suspend fun loadProjects(matches: Map<Path, JsonObject>): MutableMap<String, JsonObject> {
        val projects = mutableMapOf<String, JsonObject>()
        val projectIds =
            matches.values
                .map { it.string("modId") }
                .filter { it.isNotBlank() }
                .distinct()
        for (batch in projectIds.chunked(CURSE_FORGE_BATCH_SIZE)) {
            val body =
                JsonObject().apply {
                    add("modIds", gson.toJsonTree(batch.mapNotNull { it.toLongOrNull() }))
                }
            for (entry in api.request("mods", body).array("data")) {
                val info = runCatching { entry.asJsonObject }.getOrNull() ?: continue
                projects[info.string("id")] = info
            }
        }
        return projects
    }
}
