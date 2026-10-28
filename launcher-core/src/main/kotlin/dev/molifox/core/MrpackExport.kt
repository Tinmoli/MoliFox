// 负责Modrinth 标准整合包的导出与文件识别。

package dev.molifox.core

import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.io.path.*
import kotlinx.coroutines.*

data class MrpackExportMetadata(val name: String, val version: String, val summary: String = "")

enum class PackExportFormat(val title: String, val extension: String) {
    Modrinth("Modrinth · .mrpack（推荐）", "mrpack"),
    Mcbbs("MCBBS · .zip（PCL / HMCL）", "zip"),
}

/** A download reference never includes the resource bytes in the archive. */
data class MrpackExportFile(
    val path: String,
    val size: Long,
    val sha1: String,
    val sha512: String,
    val downloadUrl: String?,
    val selectedByDefault: Boolean,
    val requiresPermission: Boolean,
)

/** Fixed preview; exporting rechecks both the instance identity and selected file contents. */
class MrpackExportPlan
internal constructor(
    val instanceId: String,
    val metadata: MrpackExportMetadata,
    val files: List<MrpackExportFile>,
    val warnings: List<String>,
    internal val root: Path,
    internal val realRoot: Path,
    internal val components: InstallRequest,
) {
    val defaultSelection: Set<String>
        get() = files.filter { it.selectedByDefault }.map { it.path }.toSet()
}

internal class MrpackExporter(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String,
) {
    suspend fun preview(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean,
    ): MrpackExportPlan =
        withContext(Dispatchers.IO) {
            require(metadata.name.isNotBlank() && metadata.name.length <= 200) {
                "请输入整合包名称（最多 200 字）"
            }
            require(metadata.version.isNotBlank() && metadata.version.length <= 100) {
                "请输入整合包版本（最多 100 字）"
            }
            require(metadata.summary.length <= 2000) { "整合包简介最多 2000 字" }
            require(
                listOf(metadata.name, metadata.version, metadata.summary).none { '\u0000' in it }
            ) {
                "整合包信息包含无效字符"
            }
            val instance = exportableInstance(id)
            val root = store.instancePath(id).toAbsolutePath().normalize()
            require(root.isDirectory() && !Files.isSymbolicLink(root)) { "实例目录不存在或是符号链接" }
            val realRoot = root.toRealPath()
            val files = mutableListOf<MrpackExportFile>()
            // Standard component metadata cannot preserve another launcher's custom patches.
            val names = mutableSetOf<String>()
            val directories = ArrayDeque<Path>().apply { add(root) }
            while (directories.isNotEmpty()) {
                Files.newDirectoryStream(directories.removeFirst()).use { paths ->
                    for (file in paths) {
                        coroutineContext.ensureActive()
                        if (Files.isSymbolicLink(file)) continue
                        val path = root.relativize(file).joinToString("/")
                        if (excludedExportPath(path)) continue
                        if (
                            path in setOf("${instance.versionId}.jar", "${instance.versionId}.json")
                        )
                            continue
                        val top = path.substringBefore('/').lowercase(Locale.ROOT)
                        if (!store.isolated(instance) && top !in exportContentRoots) continue
                        val checked = safePath(root, path)
                        require(checked.toRealPath().startsWith(realRoot)) { "导出文件指向实例目录外：$path" }
                        if (Files.isDirectory(checked, NOFOLLOW_LINKS)) {
                            directories.add(checked)
                            continue
                        }
                        if (!Files.isRegularFile(checked, NOFOLLOW_LINKS)) continue
                        require(names.add(path.lowercase(Locale.ROOT))) { "文件名在 Windows 上冲突：$path" }
                        require(files.size < 20000) { "实例文件过多，请缩小导出范围" }
                        val hashes = hashExportFile(checked)
                        val resource =
                            top in setOf("mods", "resourcepacks", "shaderpacks") ||
                                path.substringAfterLast('.', "").lowercase(Locale.ROOT) in
                                    setOf("jar", "zip", "mrpack", "exe", "dll", "so")
                        files +=
                            MrpackExportFile(
                                path,
                                hashes.size,
                                hashes.sha1,
                                hashes.sha512,
                                null,
                                top in exportDefaultRoots && !resource,
                                resource,
                            )
                    }
                }
            }
            val warnings = mutableListOf<String>()
            val remotes = mutableMapOf<String, String>()
            if (resolveDownloads) {
                for (batch in
                    files
                        .filter { it.requiresPermission }
                        .map { it.sha512 }
                        .distinct()
                        .chunked(100)) {
                    coroutineContext.ensureActive()
                    try {
                        val body =
                            com.google.gson.JsonObject().apply {
                                addProperty("algorithm", "sha512")
                                add("hashes", gson.toJsonTree(batch))
                            }
                        val response = network.post("$api/version_files", body)
                        for (file in files.filter { it.sha512 in batch }) {
                            val version =
                                response.get(file.sha512)?.takeIf { it.isJsonObject }?.asJsonObject
                                    ?: continue
                            val remote =
                                version
                                    .array("files")
                                    .firstOrNull {
                                        it.isJsonObject &&
                                            it.asJsonObject.let { candidate ->
                                                candidate
                                                    .obj("hashes")
                                                    .string("sha512")
                                                    .equals(file.sha512, true) &&
                                                    candidate
                                                        .obj("hashes")
                                                        .string("sha1")
                                                        .equals(file.sha1, true) &&
                                                    candidate.get("size")?.asLong == file.size &&
                                                    allowedMrpackDownload(candidate.string("url"))
                                            }
                                    }
                                    ?.asJsonObject
                            if (remote != null) remotes[file.sha512] = remote.string("url")
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        warnings += "部分文件的 Modrinth 来源查询失败；未识别资源保持未选中，不会自动打包。"
                    }
                }
            }
            if (instance.externalProfile)
                warnings += "外部实例仅导出标准游戏与加载器组件；原启动器的特殊补丁、启动命令和认证配置不会写入整合包。"
            MrpackExportPlan(
                id,
                metadata,
                files
                    .sortedBy { it.path }
                    .map { file ->
                        val url = remotes[file.sha512]
                        if (url == null) file
                        else
                            file.copy(
                                downloadUrl = url,
                                selectedByDefault = true,
                                requiresPermission = false,
                            )
                    },
                warnings.distinct(),
                root,
                realRoot,
                InstallRequest(
                    instance.name,
                    instance.gameVersion,
                    instance.loader,
                    instance.loaderVersion,
                ),
            )
        }

    suspend fun export(
        plan: MrpackExportPlan,
        destination: Path,
        selected: Set<String>,
        permittedLocalFiles: Set<String>,
        context: JobContext,
        format: PackExportFormat = PackExportFormat.Modrinth,
    ) =
        withContext(Dispatchers.IO) {
            context.checkCancelled()
            validateSnapshot(plan)
            require(destination.extension.equals(format.extension, true)) {
                "此整合包格式需要 .${format.extension} 扩展名"
            }
            val target = destination.toAbsolutePath().normalize()
            // Resolve existing ancestors before creating anything, including aliases to the game
            // root.
            val resolved = resolveExportDestination(target)
            val protectedRoots =
                listOf(
                    plan.realRoot,
                    store.dataDirectory.toRealPath(),
                    store.minecraftRoot(store.instance(plan.instanceId)).toRealPath(),
                )
            require(protectedRoots.none { resolved.startsWith(it) }) {
                "请将整合包保存到游戏目录和 MoliFox 数据目录外"
            }
            require(selected.all { path -> plan.files.any { it.path == path } }) { "导出选择已失效，请重新预览" }
            val files = plan.files.filter { it.path in selected }
            val missing = files.filter {
                (it.requiresPermission ||
                    format == PackExportFormat.Mcbbs &&
                        it.path.substringBefore('/') in
                            setOf("mods", "resourcepacks", "shaderpacks")) &&
                    it.path !in permittedLocalFiles
            }
            require(missing.isEmpty()) { "以下本地资源尚未确认分发权限：${missing.joinToString { it.path }}" }
            val indexFiles = files.filter { it.downloadUrl != null }
            val dependencies = mutableMapOf("minecraft" to plan.components.gameVersion)
            val loaderKey =
                when (plan.components.loader) {
                    LoaderType.Vanilla -> null
                    LoaderType.Fabric -> "fabric-loader"
                    LoaderType.Quilt -> "quilt-loader"
                    LoaderType.Forge -> "forge"
                    LoaderType.NeoForge -> "neoforge"
                }
            if (loaderKey != null) dependencies[loaderKey] = plan.components.loaderVersion
            val manifest =
                mapOf(
                    "formatVersion" to 1,
                    "game" to "minecraft",
                    "name" to plan.metadata.name,
                    "versionId" to plan.metadata.version,
                    "summary" to plan.metadata.summary,
                    "dependencies" to dependencies,
                    "files" to
                        indexFiles.map { file ->
                            mapOf(
                                "path" to file.path,
                                "fileSize" to file.size,
                                "hashes" to mapOf("sha1" to file.sha1, "sha512" to file.sha512),
                                "downloads" to listOf(file.downloadUrl),
                                "env" to mapOf("client" to "required", "server" to "unsupported"),
                            )
                        },
                )
            val archiveManifest =
                if (format == PackExportFormat.Modrinth) manifest
                else
                    mapOf(
                        "manifestType" to "minecraftModpack",
                        "manifestVersion" to 2,
                        "name" to plan.metadata.name,
                        "version" to plan.metadata.version,
                        "description" to plan.metadata.summary,
                        "author" to "",
                        "fileApi" to "",
                        "addons" to
                            listOf(
                                mapOf("id" to "game", "version" to plan.components.gameVersion)
                            ) +
                                if (plan.components.loader == LoaderType.Vanilla) emptyList()
                                else
                                    listOf(
                                        mapOf(
                                            "id" to plan.components.loader.name.lowercase(),
                                            "version" to plan.components.loaderVersion,
                                        )
                                    ),
                        "files" to
                            files.map {
                                mapOf(
                                    "type" to "addon",
                                    "path" to it.path,
                                    "hash" to it.sha1,
                                    "force" to true,
                                )
                            },
                    )
            val manifestBytes = gson.toJson(archiveManifest).toByteArray(Charsets.UTF_8)
            val icon = InstanceIcons(store).exportImage(store.instance(plan.instanceId))
            require(manifestBytes.size <= 8 * 1024 * 1024) { "导出清单过大，请减少选择的文件" }
            target.parent.createDirectories()
            val temporary = target.resolveSibling(".${target.fileName}.${UUID.randomUUID()}.part")
            try {
                ZipOutputStream(
                        Files.newOutputStream(
                            temporary,
                            java.nio.file.StandardOpenOption.CREATE_NEW,
                        )
                    )
                    .use { zip ->
                        zip.putNextEntry(
                            ZipEntry(
                                if (format == PackExportFormat.Modrinth) "modrinth.index.json"
                                else "mcbbs.packmeta"
                            )
                        )
                        zip.write(manifestBytes)
                        zip.closeEntry()
                        if (icon != null) {
                            zip.putNextEntry(ZipEntry("icon.png"))
                            zip.write(icon)
                            zip.closeEntry()
                        }
                        for ((i, file) in files.withIndex()) {
                            context.checkCancelled()
                            context.progress(i.toFloat() / maxOf(1, files.size), "导出 ${file.path}")
                            val source = safePath(plan.root, file.path)
                            require(
                                Files.isRegularFile(source, NOFOLLOW_LINKS) &&
                                    source.toRealPath().startsWith(plan.realRoot)
                            ) {
                                "文件已移动，请重新预览：${file.path}"
                            }
                            val embed = format == PackExportFormat.Mcbbs || file.downloadUrl == null
                            if (embed)
                                zip.putNextEntry(
                                    ZipEntry(
                                        (if (format == PackExportFormat.Mcbbs) "overrides/"
                                        else "client-overrides/") + file.path
                                    )
                                )
                            val hashes =
                                hashExportFile(source) { bytes, count ->
                                    if (embed) zip.write(bytes, 0, count)
                                }
                            require(hashes == ExportHashes(file.size, file.sha1, file.sha512)) {
                                "文件已改变，请重新预览：${file.path}"
                            }
                            if (embed) zip.closeEntry()
                        }
                    }
                context.checkCancelled()
                validateSnapshot(plan)
                require(resolveExportDestination(target) == resolved) { "导出目录已改变" }
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                context.progress(1f, "${format.title} 已导出")
            } finally {
                temporary.deleteIfExists()
            }
        }

    private fun exportableInstance(id: String): GameInstance =
        store.instance(id).also {
            require(it.installed) {
                "请先完成实例安装，再导出标准整合包"
            }
            require(
                it.gameVersion.isNotBlank() &&
                    (it.loader == LoaderType.Vanilla || it.loaderVersion.isNotBlank())
            ) {
                "实例缺少明确的游戏或加载器版本"
            }
        }

    private fun validateSnapshot(plan: MrpackExportPlan) {
        val instance = exportableInstance(plan.instanceId)
        require(
            store.instancePath(instance.id).toAbsolutePath().normalize() == plan.root &&
                plan.root.toRealPath() == plan.realRoot &&
                instance.gameVersion == plan.components.gameVersion &&
                instance.loader == plan.components.loader &&
                instance.loaderVersion == plan.components.loaderVersion
        ) {
            "实例配置已改变，请重新预览"
        }
    }
}

private val exportDefaultRoots = setOf("config", "defaultconfigs", "kubejs", "scripts")
private val exportContentRoots =
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
internal fun excludedExportPath(path: String): Boolean =
    path.lowercase(Locale.ROOT).split('/').any { part ->
        part.startsWith(".molifox") ||
            part in
                setOf(
                    "molifox",
                    ".hmcl",
                    ".git",
                    ".fabric",
                    ".quilt",
                    ".cache",
                    "cache",
                    "logs",
                    "crash-reports",
                    "natives",
                    "libraries",
                    "assets",
                    "versions",
                    "backups",
                    "accounts",
                    "credentials",
                    "tokens",
                    "launcher.json",
                    "launcher.json.bak",
                    "accounts.json",
                    "hmcl.json",
                    "hmclversion.cfg",
                    "instance.cfg",
                    "launcher_profiles.json",
                    "launcher_accounts.json",
                    "launcher_msa_credentials.bin",
                    "usernamecache.json",
                    "usercache.json",
                    "realms_persistence.json",
                    "modrinth.index.json",
                    "mcbbs.packmeta",
                    "molifox-instance.json",
                    "manifest.json",
                    "modpack.json",
                    "mmc-pack.json",
                ) ||
            part.endsWith(".log") ||
            part.endsWith(".log.gz") ||
            part.endsWith(".lock")
    }

private fun allowedMrpackDownload(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.scheme == "https" &&
        uri.host?.lowercase(Locale.ROOT) in
            setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com") &&
        uri.userInfo == null &&
        uri.fragment == null &&
        uri.port in setOf(-1, 443)
}
    .getOrDefault(false)

private data class ExportHashes(val size: Long, val sha1: String, val sha512: String)

private suspend fun hashExportFile(
    path: Path,
    consume: (ByteArray, Int) -> Unit = { _, _ -> },
): ExportHashes {
    val sha1 = MessageDigest.getInstance("SHA-1")
    val sha512 = MessageDigest.getInstance("SHA-512")
    var size = 0L
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            sha1.update(buffer, 0, count)
            sha512.update(buffer, 0, count)
            size += count
            consume(buffer, count)
        }
    }
    return ExportHashes(
        size,
        sha1.digest().joinToString("") { "%02x".format(it) },
        sha512.digest().joinToString("") { "%02x".format(it) },
    )
}

private fun resolveExportDestination(path: Path): Path {
    var ancestor = path
    while (!Files.exists(ancestor, NOFOLLOW_LINKS)) ancestor = requireNotNull(ancestor.parent)
    require(!Files.isSymbolicLink(path)) { "导出目标不能是符号链接" }
    return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize()
}
