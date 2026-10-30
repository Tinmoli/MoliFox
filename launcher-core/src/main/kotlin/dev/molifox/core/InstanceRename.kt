// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例重命名：移动真实版本目录，同步同名 JSON/JAR、版本引用和记录，失败时回滚。

package dev.molifox.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/** 实例名称里不允许出现的字符：与 Windows 文件名规则一致，跨平台都按同一套规则拒绝。 */
private val invalidInstanceName = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

private val reservedInstanceName = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

/**
 * 校验用户输入的实例目录名；在改动磁盘前调用，错误信息直接展示给用户。
 *
 * 显示名就是实际目录名，因此这里同时是文件名校验：非法字符、结尾点号、系统保留名都拒绝。
 */
fun validateInstanceDirectoryName(name: String) {
    require(name.isNotBlank()) { "实例名称不能为空" }
    require(name.length <= 80) { "实例名称最多 80 个字符" }
    require(!invalidInstanceName.containsMatchIn(name)) {
        "实例名称不能包含 \\ / : * ? \" < > | 或控制字符"
    }
    require(!name.endsWith('.') && !name.endsWith(' ')) { "实例名称不能以点或空格结尾" }
    require(name != "." && name != "..") { "实例名称不能是 . 或 .." }
    require(!reservedInstanceName.matches(name)) { "实例名称是系统保留名，请换一个" }
    safeFilename(name)
}

/**
 * 把用户或整合包给的名字压成实际目录名：非法字符换成 `-`，去掉结尾点号/空格，最多 70 个字符。
 *
 * 显示名就是目录名，因此调用方把清洗结果同时作为实例名称显示；清洗后仍不合法（系统保留名、`.`、`..`）
 * 时抛出与 [validateInstanceDirectoryName] 相同的中文错误，而不是另造一个目录。
 */
fun instanceDirectoryName(preferred: String): String {
    val base =
        preferred
            .replace(invalidInstanceName, "-")
            .trim()
            .trimEnd('.')
            .take(70)
            .trimEnd('.', ' ')
            .ifBlank { "Minecraft" }
    validateInstanceDirectoryName(base)
    return base
}

/** 重命名计划：全部校验通过后才会移动磁盘；[source] 与 [target] 都在游戏根的 `versions/` 下。 */
data class InstanceRenamePlan(
    val instance: GameInstance,
    val oldName: String,
    val newName: String,
    val source: Path,
    val target: Path,
    /** 实例的版本标识就是目录名时，记录里的 `versionId` 也要一起改。 */
    val renamesVersionId: Boolean,
    /** 需要把 `inheritsFrom` 从旧名改成新名的版本配置（其它实例或未登记的版本目录）。 */
    val dependentProfiles: List<Path>,
)

/**
 * 实例重命名。
 *
 * 行为：移动整个版本目录，精确改同名 JSON/JAR 与 natives 文件夹，同步改配置 `id`、self `jar`、
 * 其它配置指向它的 `inheritsFrom` 以及已选版本；目标已存在即拒绝，提交失败整体回滚。
 * 这里先完成全部校验再动磁盘，且回滚由本类负责。
 *
 * 只改版本配置本身（`versions/<目录>/<目录>.json` 的固定字段），不对用户文件做全库字符串替换。
 */
class InstanceRename(
    private val store: LauncherStore,
) {
    /** 只校验、不写盘；供界面预览或测试复用。 */
    fun plan(
        instanceId: String,
        requestedName: String,
    ): InstanceRenamePlan {
        val instance = store.instance(instanceId)
        val newName = requestedName.trim()
        validateInstanceDirectoryName(newName)
        val source = store.versionGamePath(instance)
        val oldName = source.fileName.toString()
        require(newName != oldName) { "实例已经是这个名称" }
        val root = store.minecraftRoot(instance)
        val versions = source.parent
        require(source.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
            "实例目录不存在或不是文件夹：$source"
        }
        val target = safePath(versions, newName)
        require(!target.exists(NOFOLLOW_LINKS)) { "versions/$newName 已存在，请换一个名称" }
        val windows = store.platform.osName == "windows"
        val sameRoot =
            store.data.instances.filter {
                it.id != instance.id && taskPathKey(store.minecraftRoot(it)) == taskPathKey(root)
            }
        sameRoot
            .firstOrNull {
                it.gameDirectoryName
                    .ifBlank { it.versionId.ifBlank { it.gameVersion } }
                    .equals(newName, ignoreCase = windows)
            }?.let { clash ->
                throw IllegalArgumentException("已有实例「${clash.name}」使用这个名称")
            }
        // 另一个实例以这个目录作为基础版本时，移动它会直接破坏那个实例的版本标识。
        sameRoot
            .firstOrNull { it.versionId.equals(oldName, ignoreCase = windows) }
            ?.let { dependent ->
                throw IllegalArgumentException(
                    "versions/$oldName 仍是实例「${dependent.name}」的基础版本目录，请先处理该实例",
                )
            }
        val dependents =
            versions
                .listDirectoryEntries()
                .asSequence()
                .filter {
                    it.isDirectory(NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(it) &&
                        !it.fileName.toString().equals(oldName, ignoreCase = windows)
                }.mapNotNull { directory ->
                    val file = directory.resolve("${directory.fileName}.json")
                    if (
                        !file.isRegularFile(NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(file) ||
                        file.fileSize() > 8 * 1024 * 1024
                    ) {
                        return@mapNotNull null
                    }
                    runCatching { readObject(file).string("inheritsFrom") == oldName }
                        .getOrDefault(false)
                        .let { if (it) file else null }
                }.toList()
        return InstanceRenamePlan(
            instance = instance,
            oldName = oldName,
            newName = newName,
            source = source,
            target = target,
            renamesVersionId =
                instance.versionId == oldName &&
                    (instance.externalProfile || instance.gameDirectoryName == instance.versionId),
            dependentProfiles = dependents,
        )
    }

    /**
     * 执行重命名：目录、同名 JSON/JAR/natives、配置 `id` 与方法内自引用、其它配置的 `inheritsFrom`、
     * 组件备份清单路径和实例记录一起提交；任何一步失败都恢复原目录与文件内容再抛中文错误。
     */
    fun rename(
        instanceId: String,
        requestedName: String,
    ): GameInstance {
        val instance = store.instance(instanceId)
        val newName = requestedName.trim()
        validateInstanceDirectoryName(newName)
        val source = store.versionGamePath(instance)
        val oldName = source.fileName.toString()
        if (newName == oldName) {
            // 目录已经叫这个名字：只把显示名对齐到目录名（不移动任何文件）。
            if (instance.name == newName) {
                throw IllegalArgumentException("实例已经是这个名称")
            }
            store.updateInstance(instanceId) { it.copy(name = newName) }
            store.notify("实例名称已更新")
            return store.instance(instanceId)
        }
        val plan = plan(instanceId, newName)
        val oldJson = plan.source.resolve("$oldName.json")
        val newJson = plan.target.resolve("$newName.json")
        val oldJar = plan.source.resolve("$oldName.jar")
        val oldNatives = plan.source.resolve("$oldName-natives")
        val snapshots = LinkedHashMap<Path, ByteArray>()
        val hasJson = oldJson.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldJson)
        val hasJar = oldJar.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldJar)
        val hasNatives = oldNatives.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldNatives)
        if (hasJson) snapshots[oldJson] = Files.readAllBytes(oldJson)
        plan.dependentProfiles.forEach { file ->
            runCatching {
                if (file.fileSize() <=
                    8 * 1024 * 1024
                ) {
                    snapshots[file] = Files.readAllBytes(file)
                }
            }.onFailure { store.log("读取版本配置失败：$file（${it.message}）", true) }
        }
        var directoryMoved = false
        var jsonRenamed = false
        var jarRenamed = false
        var nativesRenamed = false
        try {
            Files.move(plan.source, plan.target)
            directoryMoved = true
            if (hasJson) {
                // 目录移动后旧配置位于 target/<旧名>.json，改名后再改写 id。
                Files.move(plan.target.resolve("$oldName.json"), newJson)
                jsonRenamed = true
                rewriteOwnProfile(newJson, oldName, newName)
            }
            if (hasJar) {
                Files.move(plan.target.resolve("$oldName.jar"), plan.target.resolve("$newName.jar"))
                jarRenamed = true
            }
            // natives 只改目录名，配置里的 ${natives_directory} 占位符不需要改。
            if (hasNatives) {
                Files.move(
                    plan.target.resolve("$oldName-natives"),
                    plan.target.resolve("$newName-natives"),
                )
                nativesRenamed = true
            }
            for (file in plan.dependentProfiles) {
                val json =
                    try {
                        readObject(file)
                    } catch (failure: Exception) {
                        throw IOException("无法更新版本引用：$file（${failure.message}）", failure)
                    }
                if (json.string("inheritsFrom") == oldName) {
                    json.addProperty("inheritsFrom", newName)
                    try {
                        writeJson(file, json)
                    } catch (failure: Exception) {
                        throw IOException("无法更新版本引用：$file（${failure.message}）", failure)
                    }
                }
            }
            store.updateInstance(instanceId) {
                it.copy(
                    name = newName,
                    gameDirectoryName =
                        if (it.gameDirectoryName.isBlank()) it.gameDirectoryName else newName,
                    versionId = if (plan.renamesVersionId) newName else it.versionId,
                )
            }
            // 组件回退清单里的路径和记录快照要跟着改，否则回退会重新写出旧目录；失败只记录，不回滚已完成的重命名。
            runCatching { syncBackupManifests(instanceId, oldName, newName) }
                .onFailure { store.log("更新组件备份路径失败：${it.message}", true) }
            store.log(
                "已重命名实例：$oldName → $newName" +
                    if (plan.dependentProfiles.isEmpty()) {
                        ""
                    } else {
                        "（同步 ${plan.dependentProfiles.size} 个版本引用）"
                    },
            )
            store.notify("实例已重命名为「$newName」")
            return store.instance(instanceId)
        } catch (failure: Exception) {
            rollback(plan, snapshots, directoryMoved, jsonRenamed, jarRenamed, nativesRenamed)
            throw IOException("重命名失败，已回滚：${failure.message ?: "未知错误"}", failure)
        }
    }

    /** 只改这个版本配置自己的 `id` 和指向自身的 `jar`/`inheritsFrom`。 */
    private fun rewriteOwnProfile(
        file: Path,
        oldName: String,
        newName: String,
    ) {
        val json = readObject(file)
        var changed = false
        if (json.string("id") != newName) {
            json.addProperty("id", newName)
            changed = true
        }
        for (key in listOf("jar", "inheritsFrom")) {
            if (json.string(key) == oldName) {
                json.addProperty(key, newName)
                changed = true
            }
        }
        if (changed) writeJson(file, json)
    }

    /**
     * 更新属于本实例的组件回退清单：`profiles[].path` 从旧目录改到新目录，内嵌的实例快照同步名称。
     * 只处理 `<备份目录>/components/<编号>/manifest.json` 中 `instance.id` 与 `root` 都匹配的清单。
     */
    private fun syncBackupManifests(
        instanceId: String,
        oldName: String,
        newName: String,
    ) {
        val current = store.instance(instanceId)
        val folder = store.directories.instanceBackups.resolve("components")
        if (!folder.isDirectory(NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) return
        val rootKey = taskPathKey(store.minecraftRoot(current))
        for (directory in folder.listDirectoryEntries()) {
            runCatching {
                if (!directory.isDirectory(NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
                    return@runCatching
                }
                val manifest = directory.resolve("manifest.json")
                if (
                    !manifest.isRegularFile(NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(manifest) ||
                    manifest.fileSize() > 8 * 1024 * 1024
                ) {
                    return@runCatching
                }
                val json = readObject(manifest)
                val owner = json.getAsJsonObject("instance") ?: return@runCatching
                if (owner.string("id") != instanceId || json.string("root") != rootKey) {
                    return@runCatching
                }
                var changed = false
                if (owner.string("name") != newName) {
                    owner.addProperty("name", newName)
                    changed = true
                }
                for (key in listOf("gameDirectoryName", "versionId")) {
                    if (owner.string(key) == oldName) {
                        owner.addProperty(key, newName)
                        changed = true
                    }
                }
                // 目录名和同名配置文件一起改：versions/<旧>/<旧>.json → versions/<新>/<新>.json。
                val prefix = "versions/$oldName/"
                json.getAsJsonArray("profiles")?.forEach { element ->
                    val record = element.asJsonObject
                    val path = record.string("path")
                    val updated =
                        when {
                            path == "versions/$oldName/$oldName.json" ->
                                "versions/$newName/$newName.json"
                            path.startsWith(prefix) ->
                                "versions/$newName/" + path.removePrefix(prefix)
                            else -> path
                        }
                    if (updated != path) {
                        record.addProperty("path", updated)
                        changed = true
                    }
                }
                if (changed) writeJson(manifest, json)
            }.onFailure {
                store.log("更新组件备份清单失败：${directory.fileName}（${it.message}）", true)
            }
        }
    }

    /** 反序恢复：先把改过的配置内容写回，再把目录和文件名移回原位。 */
    private fun rollback(
        plan: InstanceRenamePlan,
        snapshots: Map<Path, ByteArray>,
        directoryMoved: Boolean,
        jsonRenamed: Boolean,
        jarRenamed: Boolean,
        nativesRenamed: Boolean,
    ) {
        if (directoryMoved) {
            runCatching {
                if (nativesRenamed) {
                    runCatching {
                        Files.move(
                            plan.target.resolve("${plan.newName}-natives"),
                            plan.target.resolve("${plan.oldName}-natives"),
                        )
                    }
                }
                if (jarRenamed) {
                    runCatching {
                        Files.move(
                            plan.target.resolve("${plan.newName}.jar"),
                            plan.target.resolve("${plan.oldName}.jar"),
                        )
                    }
                }
                if (jsonRenamed) {
                    // 刚改名的配置内容已被改写，删掉新文件后由快照把原内容写回旧路径。
                    runCatching {
                        Files.deleteIfExists(
                            plan.target.resolve("${plan.newName}.json"),
                        )
                    }
                }
                Files.move(plan.target, plan.source)
            }.onFailure {
                store.log(
                    "回滚实例目录失败：${it.message}（${plan.source} → ${plan.target}）",
                    true,
                )
            }
        }
        snapshots.forEach { (file, bytes) ->
            runCatching { file.writeBytes(bytes) }
                .onFailure { store.log("回滚版本配置失败：$file（${it.message}）", true) }
        }
        runCatching { store.updateInstance(plan.instance.id) { plan.instance } }
            .onFailure { store.log("回滚实例记录失败：${it.message}", true) }
    }
}
