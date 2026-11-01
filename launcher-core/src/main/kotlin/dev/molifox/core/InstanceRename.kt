// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例重命名：移动真实版本目录，同步同名 JSON/JAR、版本引用和记录，失败时回滚。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
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

/** 实例名称的最大长度，目录名校验与提示文案共用。 */
private const val MAX_INSTANCE_NAME_LENGTH = 80

/** 目标名称或版本目录已被其它实例占用；界面按类型提示，不解析本地化文案。 */
class InstanceNameConflictException(
    message: String,
) : IllegalArgumentException(message)

/** 版本配置 JSON 的大小上限；超过就不再读取或快照。 */
private const val MAX_VERSION_PROFILE_BYTES = 8 * 1024 * 1024

/** 组件备份清单 JSON 的大小上限；超过就跳过该清单。 */
private const val MAX_BACKUP_MANIFEST_BYTES = 8 * 1024 * 1024

/** 重命名过程中已经完成的步骤；回滚只撤销真正执行过的部分。 */
private class RenameProgress {
    var directoryMoved = false
    var jsonRenamed = false
    var jarRenamed = false
    var nativesRenamed = false
}

/** 实例名称里不允许出现的字符：与 Windows 文件名规则一致，跨平台都按同一套规则拒绝。 */
private val invalidInstanceName = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

private val reservedInstanceName = Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?")

/**
 * 校验用户输入的实例目录名；在改动磁盘前调用，错误信息直接展示给用户。
 *
 * 显示名就是实际目录名，因此这里同时是文件名校验：非法字符、结尾点号、系统保留名都拒绝。
 */
fun validateInstanceDirectoryName(name: String) {
    require(name.isNotBlank()) { I18n.t("core.instanceRename.1") }
    require(name.length <= MAX_INSTANCE_NAME_LENGTH) { I18n.t("core.instanceRename.2") }
    require(!invalidInstanceName.containsMatchIn(name)) {
        I18n.t("core.instanceRename.3")
    }
    require(!name.endsWith('.') && !name.endsWith(' ')) {
        I18n.t("instA2.instanceRename.endsWithDotOrSpace")
    }
    require(name != "." && name != "..") { I18n.t("core.instanceRename.4") }
    require(!reservedInstanceName.matches(name)) { I18n.t("core.instanceRename.5") }
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
        require(newName != oldName) { I18n.t("core.instanceRename.6") }
        val root = store.minecraftRoot(instance)
        val versions = source.parent
        require(source.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(source)) {
            I18n.t("core.instanceRename.7", source)
        }
        val target = safePath(versions, newName)
        if (target.exists(NOFOLLOW_LINKS)) {
            throw InstanceNameConflictException(I18n.t("core.instanceRename.8", newName))
        }
        val windows = store.platform.osName == "windows"
        val siblings = sameRootInstances(store, instance, root)
        requireNoNameClash(siblings, newName, windows)
        requireNoBaseVersionUse(siblings, oldName, windows)
        return InstanceRenamePlan(
            instance = instance,
            oldName = oldName,
            newName = newName,
            source = source,
            target = target,
            renamesVersionId =
                instance.versionId == oldName &&
                    (instance.externalProfile || instance.gameDirectoryName == instance.versionId),
            dependentProfiles = dependentProfiles(versions, oldName, windows),
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
            return renameDisplayName(instanceId, instance.name, newName)
        }
        val plan = plan(instanceId, newName)
        val snapshots = snapshotVersionProfiles(store, plan)
        val progress = RenameProgress()
        val failure =
            try {
                moveVersionFiles(plan, progress)
                commitRename(instanceId, plan)
                return store.instance(instanceId)
            } catch (error: IOException) {
                error
            } catch (error: JsonParseException) {
                error
            } catch (error: IllegalStateException) {
                error
            }
        rollback(plan, snapshots, progress)
        throw renameFailure(failure)
    }

    /** 目录已经叫这个名字：只把显示名对齐到目录名，不移动任何文件。 */
    private fun renameDisplayName(
        instanceId: String,
        currentName: String,
        newName: String,
    ): GameInstance {
        if (currentName == newName) {
            throw IllegalArgumentException(I18n.t("core.instanceRename.6"))
        }
        store.updateInstance(instanceId) { it.copy(name = newName) }
        store.notify(I18n.t("core.instanceRename.9"))
        return store.instance(instanceId)
    }

    /** 移动版本目录并精确改名同名 JSON/JAR/natives，最后同步改写其它配置的 `inheritsFrom`。 */
    private fun moveVersionFiles(
        plan: InstanceRenamePlan,
        progress: RenameProgress,
    ) {
        val oldName = plan.oldName
        val newName = plan.newName
        val oldJson = plan.source.resolve("$oldName.json")
        val newJson = plan.target.resolve("$newName.json")
        val oldJar = plan.source.resolve("$oldName.jar")
        val oldNatives = plan.source.resolve("$oldName-natives")
        val hasJson = oldJson.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldJson)
        val hasJar = oldJar.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldJar)
        val hasNatives = oldNatives.isDirectory(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldNatives)
        Files.move(plan.source, plan.target)
        progress.directoryMoved = true
        if (hasJson) {
            // 目录移动后旧配置位于 target/<旧名>.json，改名后再改写 id。
            Files.move(plan.target.resolve("$oldName.json"), newJson)
            progress.jsonRenamed = true
            rewriteOwnProfile(newJson, oldName, newName)
        }
        if (hasJar) {
            Files.move(plan.target.resolve("$oldName.jar"), plan.target.resolve("$newName.jar"))
            progress.jarRenamed = true
        }
        // natives 只改目录名，配置里的 ${natives_directory} 占位符不需要改。
        if (hasNatives) {
            Files.move(
                plan.target.resolve("$oldName-natives"),
                plan.target.resolve("$newName-natives"),
            )
            progress.nativesRenamed = true
        }
        for (file in plan.dependentProfiles) {
            updateDependentProfile(file, oldName, newName)
        }
    }

    /** 提交实例记录、组件备份清单与提示；这些步骤失败只记录，不再回滚已完成的重命名。 */
    private fun commitRename(
        instanceId: String,
        plan: InstanceRenamePlan,
    ) {
        val oldName = plan.oldName
        val newName = plan.newName
        store.updateInstance(instanceId) {
            it.copy(
                name = newName,
                gameDirectoryName =
                    if (it.gameDirectoryName.isBlank()) it.gameDirectoryName else newName,
                versionId = if (plan.renamesVersionId) newName else it.versionId,
            )
        }
        // 组件回退清单里的路径和记录快照要跟着改，否则回退会重新写出旧目录。
        runCatching { syncBackupManifests(instanceId, oldName, newName) }
            .onFailure {
                store.log(I18n.t("instA2.instanceRename.syncBackupPathsFailed", it.message), true)
            }
        store.log(
            if (plan.dependentProfiles.isEmpty()) {
                I18n.t("instA2.instanceRename.renamed", oldName, newName)
            } else {
                I18n.t(
                    "instA2.instanceRename.renamedWithRefs",
                    oldName,
                    newName,
                    plan.dependentProfiles.size,
                )
            },
        )
        store.notify(I18n.t("core.instanceRename.11", newName))
    }

    /** 改写其它版本配置里指向旧目录的 `inheritsFrom`；读或写失败都按原提示中止。 */
    private fun updateDependentProfile(
        file: Path,
        oldName: String,
        newName: String,
    ) {
        val json = versionProfileIo(file) { readObject(file) }
        if (json.string("inheritsFrom") == oldName) {
            json.addProperty("inheritsFrom", newName)
            versionProfileIo(file) { writeJson(file, json) }
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
                    manifest.fileSize() > MAX_BACKUP_MANIFEST_BYTES
                ) {
                    return@runCatching
                }
                val json = readObject(manifest)
                val owner = json.getAsJsonObject("instance") ?: return@runCatching
                if (owner.string("id") != instanceId || json.string("root") != rootKey) {
                    return@runCatching
                }
                if (renameInManifest(json, owner, oldName, newName)) {
                    writeJson(manifest, json)
                }
            }.onFailure {
                store.log(
                    I18n.t("instA2.instanceRename.syncManifestFailed", directory.fileName, it.message),
                    true,
                )
            }
        }
    }

    /** 反序恢复：先把改过的配置内容写回，再把目录和文件名移回原位。 */
    private fun rollback(
        plan: InstanceRenamePlan,
        snapshots: Map<Path, ByteArray>,
        progress: RenameProgress,
    ) {
        if (progress.directoryMoved) {
            runCatching { restoreDirectory(plan, progress) }.onFailure {
                store.log(
                    I18n.t("core.instanceRename.12", it.message, plan.source, plan.target),
                    true,
                )
            }
        }
        snapshots.forEach { (file, bytes) ->
            runCatching { file.writeBytes(bytes) }
                .onFailure {
                    store.log(
                        I18n.t("instA2.instanceRename.rollbackProfileFailed", file, it.message),
                        true,
                    )
                }
        }
        runCatching { store.updateInstance(plan.instance.id) { plan.instance } }
            .onFailure {
                store.log(I18n.t("instA2.instanceRename.rollbackRecordFailed", it.message), true)
            }
    }

    /** 把已改名的 natives/JAR/JSON 逐个改回旧名，最后把整个目录移回原位置。 */
    private fun restoreDirectory(
        plan: InstanceRenamePlan,
        progress: RenameProgress,
    ) {
        if (progress.nativesRenamed) {
            runCatching {
                Files.move(
                    plan.target.resolve("${plan.newName}-natives"),
                    plan.target.resolve("${plan.oldName}-natives"),
                )
            }
        }
        if (progress.jarRenamed) {
            runCatching {
                Files.move(
                    plan.target.resolve("${plan.newName}.jar"),
                    plan.target.resolve("${plan.oldName}.jar"),
                )
            }
        }
        if (progress.jsonRenamed) {
            // 刚改名的配置内容已被改写，删掉新文件后由快照把原内容写回旧路径。
            runCatching {
                Files.deleteIfExists(
                    plan.target.resolve("${plan.newName}.json"),
                )
            }
        }
        Files.move(plan.target, plan.source)
    }
}

/** 同一游戏根下的其它实例记录；名称冲突与基础版本占用只看这些同根实例。 */
private fun sameRootInstances(
    store: LauncherStore,
    instance: GameInstance,
    root: Path,
): List<GameInstance> {
    val rootKey = taskPathKey(root)
    return store.data.instances.filter {
        it.id != instance.id && taskPathKey(store.minecraftRoot(it)) == rootKey
    }
}

/** 同根下已有实例使用目标目录名时拒绝重命名。 */
private fun requireNoNameClash(
    siblings: List<GameInstance>,
    newName: String,
    windows: Boolean,
) {
    siblings
        .firstOrNull {
            it.gameDirectoryName
                .ifBlank { it.versionId.ifBlank { it.gameVersion } }
                .equals(newName, ignoreCase = windows)
        }?.let { clash ->
            throw InstanceNameConflictException(I18n.t("core.instanceRename.nameClash", clash.name))
        }
}

/** 另一个实例以旧目录作为基础版本时拒绝移动：移动会直接破坏那个实例的版本标识。 */
private fun requireNoBaseVersionUse(
    siblings: List<GameInstance>,
    oldName: String,
    windows: Boolean,
) {
    siblings
        .firstOrNull { it.versionId.equals(oldName, ignoreCase = windows) }
        ?.let { dependent ->
            throw IllegalArgumentException(
                I18n.t("core.instanceRename.13", oldName, dependent.name),
            )
        }
}

/** 找出 `versions/` 下 `inheritsFrom` 指向旧目录的其它版本配置（跳过符号链接与非普通文件）。 */
private fun dependentProfiles(
    versions: Path,
    oldName: String,
    windows: Boolean,
): List<Path> =
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
                file.fileSize() > MAX_VERSION_PROFILE_BYTES
            ) {
                return@mapNotNull null
            }
            runCatching { readObject(file).string("inheritsFrom") == oldName }
                .getOrDefault(false)
                .let { if (it) file else null }
        }.toList()

/** 在移动任何文件前快照本实例配置与相关配置的内容，供失败回滚写回。 */
private fun snapshotVersionProfiles(
    store: LauncherStore,
    plan: InstanceRenamePlan,
): Map<Path, ByteArray> {
    val snapshots = LinkedHashMap<Path, ByteArray>()
    val oldJson = plan.source.resolve("${plan.oldName}.json")
    if (oldJson.isRegularFile(NOFOLLOW_LINKS) && !Files.isSymbolicLink(oldJson)) {
        snapshots[oldJson] = Files.readAllBytes(oldJson)
    }
    plan.dependentProfiles.forEach { file ->
        runCatching {
            if (file.fileSize() <= MAX_VERSION_PROFILE_BYTES) {
                snapshots[file] = Files.readAllBytes(file)
            }
        }.onFailure {
            store.log(I18n.t("instA2.instanceRename.readProfileFailed", file, it.message), true)
        }
    }
    return snapshots
}

/** 版本配置读写失败统一带上文件路径，转成中文 IO 错误。 */
private inline fun <T> versionProfileIo(
    file: Path,
    block: () -> T,
): T =
    try {
        block()
    } catch (failure: IOException) {
        throw IOException(I18n.t("core.instanceRename.14", file, failure.message), failure)
    } catch (failure: JsonParseException) {
        throw IOException(I18n.t("core.instanceRename.14", file, failure.message), failure)
    } catch (failure: IllegalStateException) {
        throw IOException(I18n.t("core.instanceRename.14", file, failure.message), failure)
    }

/** 重命名失败时统一包装成中文 IO 错误，保留原始原因。 */
private fun renameFailure(failure: Throwable): IOException =
    IOException(
        I18n.t("core.instanceRename.15", failure.message ?: I18n.t("instA2.common.unknownError")),
        failure,
    )

/** 把清单里属于旧目录的名称与 `profiles[].path` 改成新目录；返回是否真的改了。 */
private fun renameInManifest(
    json: JsonObject,
    owner: JsonObject,
    oldName: String,
    newName: String,
): Boolean {
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
    return changed
}
