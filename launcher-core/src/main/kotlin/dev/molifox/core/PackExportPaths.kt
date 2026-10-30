// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导出时的路径分类、日志筛查与下载来源判定。

package dev.molifox.core

import kotlinx.coroutines.CancellationException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** CurseForge 的 projectID/fileID 上限（32 位无符号整数）。 */
private const val MAX_CURSEFORGE_ID = 0xffffffffL

/** 下载链接只接受标准 HTTPS 端口。 */
private const val HTTPS_PORT = 443

/** 该文件会原样写进包内（而不是写下载/受管清单）。 */
internal fun MrpackExportFile.embeddedIn(format: PackExportFormat): Boolean =
    when (format) {
        PackExportFormat.Modrinth -> downloadUrl == null
        PackExportFormat.CurseForge ->
            curseForgeProjectId == null || curseForgeFileId == null
        PackExportFormat.MultiMc,
        PackExportFormat.Mcbbs,
        PackExportFormat.Backup,
        -> true
    }

/** 只有完整备份才扫描这些目录；非备份导出始终排除。 */
internal val backupOnlyExportRoots = setOf("logs", "crash-reports")

internal val exportDefaultRoots = setOf("config", "defaultconfigs", "kubejs", "scripts")
internal val exportContentRoots =
    exportDefaultRoots +
        setOf(
            "mods",
            "resourcepacks",
            "shaderpacks",
            "saves",
            "screenshots",
            "schematics",
            "options.txt",
            "optionsof.txt",
            "optionsshaders.txt",
            "servers.dat",
        )

/** Launcher private/runtime files are never offered, even when a user selects all candidates. */
internal fun excludedExportPath(
    path: String,
    allowLogs: Boolean = false,
): Boolean {
    if (path.endsWith(".part", true)) return true
    val parts = path.lowercase(Locale.ROOT).split('/')
    val logLike =
        parts.any { it in backupOnlyExportRoots || it.endsWith(".log") || it.endsWith(".log.gz") }
    if (logLike) {
        // 勾选日志后才写入；锁定文件始终跳过。
        return !allowLogs || parts.any { it.endsWith(".lock") }
    }
    return parts.any { part ->
        part.startsWith(".molifox") ||
            part.startsWith("credentials") ||
            part in launcherPrivateExportNames
    }
}

/** 备份里的存档、截图与日志分类；导出端与界面共用。 */
internal fun backupOnlyExportPath(path: String): Boolean =
    path.substringBefore('/').lowercase(Locale.ROOT) in backupOnlyExportRoots

internal fun saveExportPath(path: String): Boolean = path.substringBefore('/').equals("saves", true)

internal fun screenshotExportPath(path: String): Boolean =
    path.substringBefore('/').equals("screenshots", true)

internal fun preferenceExportPath(path: String): Boolean =
    '/' !in path &&
        path.lowercase(Locale.ROOT) in setOf("options.txt", "optionsof.txt", "optionsshaders.txt")

internal fun disabledModExportPath(path: String): Boolean =
    path.substringBefore('/').equals("mods", true) &&
        path.substringAfterLast('/').lowercase(Locale.ROOT).endsWith(".disabled")

/** CurseForge 指纹可识别的文件：mods/ 下的 jar（含 .disabled 副本）。 */
internal fun curseForgeCandidate(path: String): Boolean {
    if (!path.substringBefore('/').equals("mods", true)) return false
    val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
    return name.endsWith(".jar") || name.endsWith(".jar.disabled")
}

internal fun curseForgeIds(info: com.google.gson.JsonObject): Pair<Long, Long>? {
    val project =
        info.get("modId")?.let { runCatching { it.asLong }.getOrNull() } ?: return null
    val file = info.get("id")?.let { runCatching { it.asLong }.getOrNull() } ?: return null
    return if (project in 1..MAX_CURSEFORGE_ID && file in 1..MAX_CURSEFORGE_ID) {
        project to file
    } else {
        null
    }
}

private val exportSecretPatterns =
    listOf(
        Regex("(?i)access[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)refresh[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)session[_-]?token\"?\\s*[=:]\\s*\"?[A-Za-z0-9._-]{16,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]{20,}"),
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
    )

/** 日志内容筛查：即使勾选包含日志，疑似含凭据的单个文件也不会进入备份。 */
internal fun exportLogContainsSecret(path: Path): Boolean =
    try {
        Files.newInputStream(path).use { input ->
            val text = input.readNBytes(4 shl 20).toString(Charsets.UTF_8)
            exportSecretPatterns.any { it.containsMatchIn(text) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        true
    }

internal fun allowedMrpackDownload(value: String): Boolean =
    runCatching {
        val uri = URI(value)
        uri.scheme == "https" &&
            uri.host?.lowercase(Locale.ROOT) in
            setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com") &&
            uri.userInfo == null &&
            uri.fragment == null &&
            uri.port in setOf(-1, HTTPS_PORT)
    }.getOrDefault(false)
