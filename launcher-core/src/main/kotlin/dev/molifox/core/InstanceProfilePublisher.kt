// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责把合成后的版本配置与客户端 JAR 发布到实例目录。

package dev.molifox.core

import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

/** 实例启动资料的发布与客户端 JAR 定位。 */
internal class InstanceProfilePublisher(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val catalog: MinecraftVersionCatalog,
) {
    /**
     * 客户端 JAR 的真实地址：只用版本 JSON 的 `downloads.client.url`。JSON 没给时，
     * 先读本机同名的完整版本配置，再按版本标识回查版本清单里的官方/镜像 JSON，
     * 不从对象存储按名称拼地址（那样只会得到 404）。
     *
     * 只取 `downloads.client.url`，缺失就留空而不是按名称猜路径（猜出来只会 404）；
     * 这里额外通过版本清单找到真实的该版本 JSON。
     */
    internal suspend fun clientJarUrl(
        profile: ResolvedProfile,
        root: Path,
    ): String {
        val declared =
            profile.json
                .obj("downloads")
                .obj("client")
                .string("url")
        if (declared.isNotBlank()) return declared
        val ids = linkedSetOf(profile.jarId, profile.id)
        runCatching { VersionResolver(root, store.platform).minecraftVersion(profile) }
            .getOrNull()
            ?.let { ids += it }
        val candidates = ids.filter { it.isNotBlank() }
        return localClientJarUrl(candidates, root)
            ?: remoteClientJarUrl(candidates)
            ?: throw IOException(
                I18n.t("instA2.profilePublisher.missingClientJar", profile.id),
            )
    }

    /** 在本机 `versions/<id>/<id>.json` 里找客户端 JAR 地址；没有就返回 null。 */
    private fun localClientJarUrl(
        candidates: List<String>,
        root: Path,
    ): String? =
        candidates.firstNotNullOfOrNull { id ->
            runCatching { safePath(root, "versions/${safeFilename(id)}/$id.json") }
                .getOrNull()
                ?.let { file ->
                    runCatching {
                        readObject(file).obj("downloads").obj("client").string("url")
                    }.getOrNull()
                }?.takeIf { it.isNotBlank() }
        }

    /** 本机找不到时回查版本清单里的官方/镜像 JSON；都没有就返回 null。 */
    private suspend fun remoteClientJarUrl(candidates: List<String>): String? =
        candidates.firstNotNullOfOrNull { id ->
            runCatching { catalog.versions().find { version -> version.id == id } }
                .getOrNull()
                ?.let { metadata ->
                    runCatching {
                        network
                            .json(metadata.url)
                            .asJsonObject
                            .obj("downloads")
                            .obj("client")
                            .string("url")
                    }.getOrNull()
                }?.takeIf { it.isNotBlank() }
        }

    /**
     * 发布目标实例的启动资料：`versions/<实例名>/<实例名>.json`，需要时再复制 `<实例名>.jar`。
     *
     * 基础版本目录由本任务新建（或就是目标目录）时合成**自包含**配置：保留主类、参数规则、库、
     * 资源索引、日志和真实游戏版本，去掉 `inheritsFrom`/`jar`，把基础 JAR 复制成 `<实例名>.jar`，
     * 之后就能安全删掉基础目录；基础目录本来就存在时保留它，配置用 `inheritsFrom` 指向它。
     */
    fun publishInstanceProfile(
        instance: GameInstance,
        base: ResolvedProfile,
        loader: JsonObject?,
        target: Path,
        root: Path,
        selfContained: Boolean,
        context: JobContext,
    ) {
        val id = target.fileName.toString()
        safeFilename(id)
        val profile =
            if (loader == null) base.json.deepCopy() else mergeVersionProfiles(base.json, loader)
        profile.addProperty("id", id)
        // 真实游戏版本单独记录：Java/资源/加载器匹配和目录扫描（InstanceCatalog）都读它。
        profile.addProperty("_minecraftVersion", instance.gameVersion)
        profile.addProperty(
            MINECRAFT_VERSION_TYPE_MARKER,
            instance.gameVersionType.ifBlank { base.json.string("type") },
        )
        profile.addProperty("gameVersion", instance.gameVersion)
        // 本启动器生成的配置：扫描时按受管实例处理（可以修复/改加载器），不会被当成外部整合包。
        profile.addProperty(MANAGED_PROFILE_MARKER, true)
        val file = target.resolve("$id.json")
        // 实例曾经以自包含方式安装过时，目录里会留着自己的客户端 JAR。之后基础版本目录
        // 已存在，配置改成 inheritsFrom，但这个 JAR 仍可能被旧配置或修复流程引用；
        // 只要它存在就一起修复，避免留下与基础版本不一致的损坏副本。
        val instanceJar = target.resolve("$id.jar")
        if (selfContained) {
            profile.remove("inheritsFrom")
            profile.remove("jar")
            // 加载器配置可能带自己的 downloads；真实客户端 JAR 仍然只取基础版本那一份。
            if (!profile
                    .obj(
                        "downloads",
                    ).has("client") && base.json.obj("downloads").has("client")
            ) {
                profile.add("downloads", base.json.obj("downloads").deepCopy())
            }
            context.writeOutput(file) { writeJson(file, profile) }
            copyBaseJar(root, base, target, context)
        } else {
            profile.addProperty("inheritsFrom", base.id)
            // 客户端 JAR 继续由基础版本目录提供，避免配置被误判成自己拥有 JAR。
            profile.remove("jar")
            profile.remove("downloads")
            context.writeOutput(file) { writeJson(file, profile) }
            if (instanceJar.isRegularFile(NOFOLLOW_LINKS)) {
                copyBaseJar(root, base, target, context)
            }
        }
    }

    /** 把基础版本的客户端 JAR 复制成 `<实例名>.jar`；自包含配置删掉基础目录后仍能启动和校验。 */
    private fun copyBaseJar(
        root: Path,
        base: ResolvedProfile,
        target: Path,
        context: JobContext,
    ) {
        val jarId = runCatching { safeFilename(base.jarId) }.getOrNull() ?: return
        val source = root.resolve("versions").resolve(jarId).resolve("$jarId.jar")
        val id = target.fileName.toString()
        val destination = target.resolve("$id.jar")
        if (source.toAbsolutePath().normalize() == destination.toAbsolutePath().normalize()) return
        if (!source.isRegularFile(NOFOLLOW_LINKS)) return
        context.writeOutput(destination) {
            destination.parent.createDirectories()
            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
