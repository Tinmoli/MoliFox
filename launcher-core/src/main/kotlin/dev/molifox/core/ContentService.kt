package dev.molifox.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlinx.coroutines.*

class ContentService(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val installer: MinecraftInstaller,
    private val api: String = "https://api.modrinth.com/v2",
    curseForgeApi: String = "https://api.curseforge.com/v1",
) {
    private val metadataReader = ModMetadataReader()
    private val curseForge = CurseForgeBackend(store, network, curseForgeApi)
    private val backend = ContentBackends(ModrinthBackend(network, api), curseForge)
    val catalog = ContentCatalog(store, network, api, backend, curseForge)

    suspend fun installDatapack(
        hit: ContentHit,
        versionId: String,
        instanceId: String,
        worldName: String,
        context: JobContext,
    ) =
        DatapackInstaller(
                store,
                network,
                backend,
                if (hit.id.startsWith("curseforge:")) curseForge.api else api,
            )
            .install(hit, versionId, instanceId, worldName, context)

    suspend fun planContent(
        hit: ContentHit,
        instanceId: String,
        selectedOptional: Set<String> = emptySet(),
        versionId: String = "",
        sources: List<ContentSource> = emptyList(),
    ): ContentInstallPlan =
        ContentInstallPlanner(store, network, api, backend)
            .plan(hit, instanceId, selectedOptional, versionId, sources)

    suspend fun planUpdates(
        report: ContentUpdateReport,
        selected: Set<String>,
    ): ContentInstallPlan {
        val root = store.instancePath(report.instanceId)
        val registryFile = root.resolve(".molifox-content.json")
        require(
            contentSignature(store, store.instance(report.instanceId)) == report.signature &&
                (if (registryFile.exists()) digest(registryFile) else "") == report.registryHash
        ) {
            "资源列表已改变，请重新检查更新"
        }
        val items = report.items.filter { it.key in selected }
        require(
            items.isNotEmpty() &&
                items.size == selected.size &&
                items.all { it.update != null && it.source != null && !it.source.locked }
        ) {
            "更新选择无效"
        }
        val requests = items.map { item ->
            val source = requireNotNull(item.source)
            ContentRequest(
                ContentHit(source.projectId, "", source.title, "", "", 0, source.kind, null),
                requireNotNull(item.update).id,
            )
        }
        return ContentInstallPlanner(store, network, api, backend)
            .planMany(
                requests,
                report.instanceId,
                emptySet(),
                report.items.mapNotNull { it.source },
            )
    }

    suspend fun installPlan(plan: ContentInstallPlan, context: JobContext) =
        ContentInstallTransaction(
                store,
                network,
                if (plan.files.all { it.projectId.startsWith("curseforge:") }) curseForge.api
                else api,
            )
            .install(plan, context)

    suspend fun search(
        query: String,
        kind: String,
        instanceId: String? = null,
        offset: Int = 0,
        filter: ContentFilter? = null,
    ): ContentSearch {
        require(
            query.length <= 200 &&
                kind in listOf("mod", "modpack", "resourcepack", "shader", "datapack") &&
                offset in 0..10000
        ) {
            "搜索参数无效"
        }
        if (store.data.settings.contentDownloadSource == "curseforge")
            return curseForge.search(query, kind, instanceId?.let(store::instance), offset, filter)
        val facets = mutableListOf(listOf("project_type:$kind"))
        val selection =
            filter
                ?: instanceId
                    ?.takeIf { kind != "modpack" }
                    ?.let {
                        store.instance(it).let { instance ->
                            ContentFilter(instance.gameVersion, instance.loader)
                        }
                    }
                ?: ContentFilter()
        if (selection.gameVersion.isNotBlank()) {
            facets += listOf("versions:${gson.toJson(selection.gameVersion)}")
        }
        if (kind == "mod" && selection.loader != null && selection.loader != LoaderType.Vanilla)
            facets += listOf("categories:${selection.loader.name.lowercase()}")
        // 分类：同一子列表内的条件是「或」，与其他子列表之间是「与」，所以多选分类合成一组。
        if (selection.categories.isNotEmpty())
            facets += selection.categories.sorted().map { "categories:${gson.toJson(it)}" }
        val parameters =
            "query=${encoded(query)}&facets=${encoded(gson.toJson(facets))}&limit=12&offset=$offset&index=${if (query.isBlank()) "downloads" else "relevance"}"
        val result = network.json("$api/search?$parameters").asJsonObject
        return ContentSearch(
            result.array("hits").map { element ->
                val item = element.asJsonObject
                ContentHit(
                    item.string("project_id"),
                    item.string("slug"),
                    item.string("title"),
                    item.string("description"),
                    item.string("author"),
                    item.get("downloads")?.asLong ?: 0,
                    item.string("project_type"),
                    item.string("icon_url").takeIf { it.isNotBlank() },
                )
            },
            result.int("total_hits"),
        )
    }

    suspend fun installContent(hit: ContentHit, instanceId: String?, context: JobContext) {
        if (hit.type == "modpack") {
            importPack(downloadPack(hit, context), context)
        } else {
            val instance = instanceId?.let { store.instance(it) } ?: error("请先选择一个游戏实例")
            require(instance.installed) { "请先完成游戏实例安装" }
            require(hit.type != "mod" || instance.loader != LoaderType.Vanilla) {
                "原版无法加载模组，请选择带加载器的实例"
            }
            installPlan(planContent(hit, instance.id), context)
        }
    }

    suspend fun downloadPack(hit: ContentHit, context: JobContext, versionId: String = ""): Path {
        require(hit.type == "modpack")
        context.progress(0f, "读取整合包版本…")
        if (hit.id.startsWith("curseforge:")) {
            val available =
                if (versionId.isBlank()) curseForge.available(hit.id)
                else listOf(backend.version(versionId))
            val version =
                available.firstOrNull { it.string("version_type") == "release" }
                    ?: available.firstOrNull()
                    ?: error("整合包没有可用版本")
            require(
                version.string("project_id") == hit.id &&
                    (versionId.isBlank() || version.string("id") == versionId)
            ) {
                "整合包版本不属于当前项目"
            }
            val file = version.array("files").first().asJsonObject
            if (version.boolean("manual_download"))
                throw ManualContentRequired(
                    listOf(
                        ManualContentFile(
                            hit.id,
                            version.string("id"),
                            file.string("filename"),
                            version.string("website"),
                        )
                    )
                )
            val archive =
                safePath(
                    store.downloadDirectory,
                    "packs/curseforge-${curseForgeProjectId(hit.id)}/${safeFilename(file.string("filename"))}",
                )
            require(archive.name.endsWith(".zip", true)) { "CurseForge 整合包必须为 ZIP" }
            network.download(
                DownloadArtifact(
                    contentFileUrl(curseForge.api, file.string("url")),
                    archive,
                    file.obj("hashes").string("sha1"),
                    file.get("size").asLong,
                )
            ) { bytes, total ->
                context.progress(
                    if (total > 0) bytes.toFloat() / total else 0f,
                    "下载整合包 · ${bytes / 1024 / 1024} MB",
                )
            }
            return archive
        }
        val available =
            if (versionId.isNotBlank()) listOf(backend.version(versionId))
            else
                network.json("$api/project/${encoded(hit.id)}/version").asJsonArray.map {
                    it.asJsonObject
                }
        val version =
            available
                .sortedByDescending { it.string("date_published") }
                .let { all ->
                    all.firstOrNull { it.string("version_type") == "release" } ?: all.firstOrNull()
                } ?: error("整合包没有可用版本")
        require(
            version.string("project_id") == hit.id &&
                (versionId.isBlank() || version.string("id") == versionId)
        ) {
            "整合包版本不属于当前项目"
        }
        val file =
            version
                .array("files")
                .map { it.asJsonObject }
                .find { it.string("filename").endsWith(".mrpack") } ?: error("此项目不提供 .mrpack 文件")
        val archive =
            safePath(
                safePath(store.downloadDirectory, "packs/${safeFilename(hit.id)}"),
                safeFilename(file.string("filename")),
            )
        val sha512 = file.obj("hashes").string("sha512")
        val sha1 = file.obj("hashes").string("sha1")
        require(
            (sha512.isBlank() || sha512.matches(Regex("[a-fA-F0-9]{128}"))) &&
                (sha1.isBlank() || sha1.matches(Regex("[a-fA-F0-9]{40}"))) &&
                (sha512.isNotBlank() || sha1.isNotBlank())
        ) {
            "整合包下载缺少有效校验信息"
        }
        network.download(
            DownloadArtifact(
                contentFileUrl(api, file.string("url")),
                archive,
                sha512.ifBlank { sha1 },
                file.get("size")?.asLong ?: 0,
                if (sha512.isNotBlank()) "SHA-512" else "SHA-1",
            )
        ) { bytes, total ->
            context.progress(
                if (total > 0) bytes.toFloat() / total else 0f,
                "下载整合包 · ${bytes / 1024 / 1024} MB",
            )
        }
        return archive
    }

    fun listMods(id: String): List<ModFile> {
        val directory = store.instancePath(id).resolve("mods")
        if (!directory.exists()) return emptyList()
        return directory
            .listDirectoryEntries()
            .filter {
                it.isRegularFile() &&
                    !Files.isSymbolicLink(it) &&
                    Regex("(?i).*\\.jar(?:\\.disabled)?").matches(it.name)
            }
            .map {
                ModFile(
                    it.name,
                    it.fileSize(),
                    !it.name.endsWith(".disabled", true),
                    metadataReader.read(it),
                    it.getLastModifiedTime().toMillis(),
                )
            }
            .sortedBy { it.filename.lowercase() }
    }

    fun toggleMod(id: String, filename: String) {
        require(Regex("(?i).*\\.jar(?:\\.disabled)?").matches(filename)) { "无效的模组文件" }
        val directory = store.instancePath(id).resolve("mods")
        val source = safePath(directory, safeFilename(filename))
        val target =
            safePath(
                directory,
                if (filename.endsWith(".disabled", true)) contentBaseFilename(filename)
                else "$filename.disabled",
            )
        require(!target.exists()) { "目标文件已存在，请处理重复模组" }
        Files.move(source, target)
    }

    fun deleteMod(id: String, filename: String) {
        require(Regex("(?i).*\\.jar(?:\\.disabled)?").matches(filename))
        store.platform.moveToTrash(
            safePath(store.instancePath(id).resolve("mods"), safeFilename(filename))
        )
    }

    fun importMods(id: String, files: List<Path>) {
        require(store.instance(id).loader != LoaderType.Vanilla) { "请先选择带加载器的实例" }
        val destination = store.instancePath(id).resolve("mods").apply { createDirectories() }
        for (file in files) {
            require(file.name.endsWith(".jar", true))
            Files.copy(file, safePath(destination, safeFilename(file.name)))
        }
    }

    /** 原理图（世界编辑 litematic/schem 等）导入到实例的 schematics 目录。 */
    fun importSchematics(id: String, files: List<Path>) {
        require(files.isNotEmpty()) { "请选择原理图文件" }
        val destination = store.instancePath(id).resolve("schematics").apply { createDirectories() }
        for (file in files) {
            require(Regex("(?i).*\\.(litematic|schem|schematic|nbt)").matches(file.name)) {
                "仅支持 .litematic / .schem / .schematic / .nbt 原理图"
            }
            require(runCatching { file.fileSize() }.getOrDefault(0L) <= 256L * 1024 * 1024) {
                "原理图文件过大（上限 256 MB）"
            }
            Files.copy(file, safePath(destination, safeFilename(file.name)))
        }
    }

    fun deleteSchematic(id: String, filename: String) {
        require(Regex("(?i).*\\.(litematic|schem|schematic|nbt)").matches(filename)) { "无效的原理图文件" }
        store.platform.moveToTrash(
            safePath(store.instancePath(id).resolve("schematics"), safeFilename(filename))
        )
    }

    private suspend fun <T> withPackSnapshot(archive: Path, action: suspend (Path) -> T): T =
        withContext(Dispatchers.IO) {
            val snapshot =
                Files.createTempFile(
                    store.temporaryDirectory.apply { createDirectories() },
                    "pack-import-",
                    ".zip",
                )
            try {
                Files.copy(archive, snapshot, StandardCopyOption.REPLACE_EXISTING)
                action(snapshot)
            } finally {
                snapshot.deleteIfExists()
            }
        }

    suspend fun previewPack(archive: Path): PackPreview =
        withPackSnapshot(archive) { snapshot ->
            ZipFile(snapshot.toFile()).use { zip ->
                val manifest =
                    readPackManifest(
                        zip,
                        store.temporaryDirectory.resolve("pack-validation"),
                        store.platform.osName,
                    )
                installer.validate(manifest.request)
                PackPreview(
                    archive.toAbsolutePath().toString(),
                    manifest.request.name,
                    manifest.optionalFiles,
                    digest(snapshot, "SHA-256"),
                    manifest.request.gameVersion,
                    manifest.request.loader,
                    manifest.request.loaderVersion,
                    manifest.version,
                    manifest.summary,
                    manifest.requiredFiles,
                )
            }
        }

    suspend fun importPack(
        archive: Path,
        context: JobContext,
        repairId: String? = null,
        optionalFiles: Set<String>? = null,
        expectedHash: String? = null,
    ) =
        withPackSnapshot(archive) { snapshot ->
            require(
                expectedHash == null || digest(snapshot, "SHA-256").equals(expectedHash, true)
            ) {
                "整合包文件已改变，请重新选择"
            }
            ZipFile(snapshot.toFile()).use { zip ->
                val manifest =
                    readPackManifest(
                        zip,
                        store.temporaryDirectory.resolve("pack-validation"),
                        store.platform.osName,
                    )
                val index = manifest.index
                val request = manifest.request
                installer.validate(request)
                val launchDefaults =
                    if (repairId == null)
                        manifest.launchOptions?.resolve(store.data.settings, store.platform.osName)
                    else null
                if (repairId != null) {
                    val existing = store.instance(repairId)
                    require(
                        existing.gameVersion == request.gameVersion &&
                            existing.loader == request.loader &&
                            existing.loaderVersion == request.loaderVersion
                    ) {
                        "修复包的游戏版本或加载器与实例不符，请新建实例导入；修复不能替代升级"
                    }
                }
                require(
                    optionalFiles == null || optionalFiles.all { it in manifest.optionalFiles }
                ) {
                    "所选可选文件已不在整合包中，请重新选择"
                }
                val selection =
                    optionalFiles ?: repairId?.let { store.instance(it).packOptionalFiles?.toSet() }
                val curseForgeFiles =
                    if (manifest.curseForge == null) emptyList()
                    else
                        prepareCurseForgePack(
                            manifest,
                            curseForge,
                            selection,
                            archive.toAbsolutePath().parent.resolve("manual-downloads"),
                            context,
                            repairId?.let(store::instancePath),
                        )
                val instance =
                    repairId?.let { store.instance(it) }
                        ?: installer.createInstance(request).let { created ->
                            store.updateInstance(created.id) {
                                it.copy(
                                    isolation = VersionIsolation.Enabled,
                                    extraJvmArgs = launchDefaults?.jvm ?: it.extraJvmArgs,
                                    advancedLaunch = launchDefaults?.advanced ?: it.advancedLaunch,
                                )
                            }
                            store.instance(created.id)
                        }
                var root = store.instancePath(instance.id)
                // Validate every override path before any download or extraction.
                for (prefix in manifest.overridePrefixes) validateArchive(zip, prefix, root)
                val savedPack =
                    store.directories.packArchives.resolve(
                        "${instance.id}.${if (index != null) "mrpack" else "zip"}"
                    )
                savedPack.parent.createDirectories()
                val replacement =
                    Files.createTempFile(savedPack.parent, "${savedPack.fileName}.", ".part")
                try {
                    Files.copy(snapshot, replacement, StandardCopyOption.REPLACE_EXISTING)
                    try {
                        Files.move(
                            replacement,
                            savedPack,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE,
                        )
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(replacement, savedPack, StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally {
                    replacement.deleteIfExists()
                }
                store.updateInstance(instance.id) {
                    it.copy(
                        packFile = savedPack.toString(),
                        installed = false,
                        packOptionalFiles = selection?.toList(),
                    )
                }
                var transaction: PackFileTransaction? = null
                try {
                    installer.install(instance.id, context)
                    root = store.instancePath(instance.id)
                    val staged = PackFileTransaction(store, root)
                    transaction = staged
                    store.updateInstance(instance.id) { it.copy(installed = false) }
                    stageCurseForgePackFiles(curseForgeFiles, staged, context) {
                        network.download(it)
                    }
                    stagePackFiles(
                        manifest,
                        zip,
                        staged,
                        root,
                        selection,
                        repairId != null,
                        context,
                    ) {
                        network.download(it)
                    }
                    staged.commit(context)
                    runCatching {
                        PackOwnershipStore.write(
                            root,
                            PackOwnershipStore.buildOwnership(
                                manifest,
                                digest(snapshot, "SHA-256"),
                                selection,
                                zip,
                                root,
                            ),
                        )
                    }
                        .onFailure {
                            store.log("整合包文件归属账本写入失败，下次升级将按迁移模式处理：${it.message}", true)
                        }
                    store.updateInstance(instance.id) { it.copy(installed = true) }
                    runCatching { applyPackIcon(store, instance.id, zip, manifest) }
                        .onFailure {
                            if (it is CancellationException) throw it
                            store.log("整合包图标未采用，使用已有或默认图标：${it.message}", true)
                        }
                } catch (error: Exception) {
                    store.updateInstance(instance.id) {
                        it.copy(installed = repairId != null && instance.installed)
                    }
                    throw error
                } finally {
                    transaction?.cleanup()
                }
            }
        }

    /** 只扫描升级计划可能涉及的路径，避免遍历整个实例目录。 */
    private fun scanPackFileStates(root: Path, paths: Set<String>): Map<String, PackFileState> =
        paths.associateWith { relative ->
            val target = packPath(root, relative)
            if (!target.isRegularFile() || Files.isSymbolicLink(target)) PackFileState(false, null)
            else PackFileState(true, runCatching { digest(target) }.getOrNull())
        }

    /** 没有账本的老实例：优先用旧归档推断，再用实例记录补齐版本与组件信息。 */
    private fun legacyPackOwnership(
        instance: GameInstance,
        root: Path,
        selection: Set<String>?,
    ): PackOwnership {
        val archive =
            instance.packFile
                .takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { Path.of(path) }.getOrNull() }
        val baseline =
            PackOwnershipStore.migrationBaseline(
                root,
                archive,
                null,
                selection,
                store.platform.osName,
            )
        return baseline.copy(
            name = baseline.name.ifBlank { instance.name },
            gameVersion = baseline.gameVersion.ifBlank { instance.gameVersion },
            loader = baseline.loader.ifBlank { instance.loader.name },
            loaderVersion = baseline.loaderVersion.ifBlank { instance.loaderVersion },
            versionId = baseline.versionId.ifBlank { instance.versionId },
        )
    }

    private fun preparePackUpgrade(
        instance: GameInstance,
        root: Path,
        snapshot: Path,
        zip: ZipFile,
        policy: PackUpgradePolicy,
    ): PreparedPackUpgrade {
        val manifest =
            readPackManifest(
                zip,
                store.temporaryDirectory.resolve("pack-validation"),
                store.platform.osName,
            )
        installer.validate(manifest.request)
        require(manifest.curseForge == null) { "CurseForge 整合包暂不支持原地升级，请重新导入" }
        val selection =
            instance.packOptionalFiles?.let { chosen ->
                manifest.optionalFiles.filter { it in chosen }.toSet()
            }
        val ownership =
            PackOwnershipStore.buildOwnership(
                manifest,
                digest(snapshot, "SHA-256"),
                selection,
                zip,
                root,
            )
        val old =
            PackOwnershipStore.read(root, store) ?: legacyPackOwnership(instance, root, selection)
        val paths = (old.files.map { it.path } + ownership.files.map { it.path }).toSet()
        val plan =
            PackUpgradePlanner.plan(
                instance.id,
                old,
                ownership,
                scanPackFileStates(root, paths),
                policy,
            )
        return PreparedPackUpgrade(manifest, ownership, plan, selection)
    }

    suspend fun previewPackUpgrade(
        instanceId: String,
        archive: Path,
        policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
    ): PackUpgradePlan =
        withPackSnapshot(archive) { snapshot ->
            val instance = store.instance(instanceId)
            require(instance.packFile.isNotBlank()) { "该实例不是整合包实例，无法升级" }
            val root = store.instancePath(instanceId)
            ZipFile(snapshot.toFile()).use { zip ->
                preparePackUpgrade(instance, root, snapshot, zip, policy).plan
            }
        }

    suspend fun upgradePack(
        instanceId: String,
        archive: Path,
        expectedArchiveSha256: String,
        allowComponentChange: Boolean,
        context: JobContext,
        policy: PackUpgradePolicy = PackUpgradePolicy(restoreUserDeleted = true),
    ): PackUpgradePlan =
        withPackSnapshot(archive) { snapshot ->
            require(
                expectedArchiveSha256.isNotBlank() &&
                    digest(snapshot, "SHA-256").equals(expectedArchiveSha256, true)
            ) {
                "升级计划已过期，请重新预览"
            }
            val instance = store.instance(instanceId)
            require(instance.installed) { "请先完成游戏实例安装" }
            require(instance.packFile.isNotBlank()) { "该实例不是整合包实例，无法升级" }
            val root = store.instancePath(instanceId)
            ZipFile(snapshot.toFile()).use { zip ->
                val prepared = preparePackUpgrade(instance, root, snapshot, zip, policy)
                require(!prepared.plan.requiresComponentChange || allowComponentChange) {
                    "整合包更改了游戏版本或加载器，需先切换组件再升级"
                }
                val savedPack =
                    store.directories.packArchives.resolve(
                        "${instance.id}.${if (prepared.manifest.index != null) "mrpack" else "zip"}"
                    )
                savedPack.parent.createDirectories()
                val staged = PackFileTransaction(store, root)
                try {
                    stagePackFiles(
                        prepared.manifest,
                        zip,
                        staged,
                        root,
                        prepared.selection,
                        false,
                        context,
                    ) {
                        network.download(it)
                    }
                    context.checkCancelled()
                    val transaction =
                        PackUpgradeTransaction(
                            store,
                            root,
                            root.resolve(".molifox-pack-upgrade-${UUID.randomUUID()}"),
                        )
                    try {
                        transaction.apply(
                            plan = prepared.plan,
                            staging = staged.content,
                            archiveSource = snapshot,
                            archiveTarget = savedPack,
                            ownership = prepared.ownership,
                            instanceId = instance.id,
                            updateInstance = {
                                it.copy(
                                    packFile = savedPack.toString(),
                                    installed = true,
                                    packOptionalFiles = prepared.selection?.toList(),
                                )
                            },
                            context = context,
                        )
                        runCatching { applyPackIcon(store, instanceId, zip, prepared.manifest) }
                            .onFailure {
                                if (it is CancellationException) throw it
                                store.log("升级包图标未采用，保留原有图标：${it.message}", true)
                            }
                    } finally {
                        transaction.cleanup()
                    }
                } finally {
                    staged.cleanup()
                }
                prepared.plan
            }
        }

    suspend fun previewMrpackExport(
        id: String,
        metadata: MrpackExportMetadata,
        resolveDownloads: Boolean = true,
    ): MrpackExportPlan =
        MrpackExporter(store, network, api).preview(id, metadata, resolveDownloads)

    suspend fun exportMrpack(
        plan: MrpackExportPlan,
        destination: Path,
        context: JobContext,
        selected: Set<String> = plan.defaultSelection,
        permittedLocalFiles: Set<String> = emptySet(),
        format: PackExportFormat = PackExportFormat.Modrinth,
    ) =
        MrpackExporter(store, network, api)
            .export(plan, destination, selected, permittedLocalFiles, context, format)

    fun exportInstance(id: String, destination: Path) {
        val instance = store.instance(id)
        require(
            instance.installed &&
                (!instance.externalProfile || instance.loader == LoaderType.Vanilla)
        ) {
            "外部加载器实例请先用原启动器导出，未完成的实例无法导出"
        }
        val root = store.instancePath(id)
        destination.parent?.createDirectories()
        require(
            !destination.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())
        ) {
            "请将整合包保存到实例目录外"
        }
        val temporary = destination.resolveSibling("${destination.fileName}.part")
        try {
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                zip.putNextEntry(ZipEntry("molifox-instance.json"))
                zip.write(
                    gson
                        .toJson(
                            mapOf(
                                "formatVersion" to 1,
                                "instance" to
                                    InstallRequest(
                                        instance.name,
                                        instance.gameVersion,
                                        instance.loader,
                                        instance.loaderVersion,
                                    ),
                            )
                        )
                        .toByteArray(Charsets.UTF_8)
                )
                zip.closeEntry()
                Files.walk(root).use { paths ->
                    paths
                        .filter { it.isRegularFile() && !Files.isSymbolicLink(it) }
                        .forEach { file ->
                            val relative = root.relativize(file)
                            if (
                                (!store.isolated(instance) || instance.gameDirectoryId != null) &&
                                    relative.first().toString() !in
                                        setOf(
                                            "mods",
                                            "saves",
                                            "config",
                                            "resourcepacks",
                                            "shaderpacks",
                                            "screenshots",
                                            "defaultconfigs",
                                            "kubejs",
                                            "scripts",
                                            "options.txt",
                                            "optionsof.txt",
                                            "servers.dat",
                                        )
                            )
                                return@forEach
                            if (
                                relative.any {
                                    it.toString() in
                                        setOf(
                                            "natives",
                                            "logs",
                                            "crash-reports",
                                            ".molifox-classpath.jar",
                                            ".molifox-content.json",
                                        )
                                }
                            )
                                return@forEach
                            zip.putNextEntry(ZipEntry("files/${relative.joinToString("/")}"))
                            Files.copy(file, zip)
                            zip.closeEntry()
                        }
                }
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.deleteIfExists()
        }
        store.log("实例已导出（包含存档、模组和配置）：$destination")
    }
}

/** 升级预览与执行共用的中间结果：清单、新账本、计划与可选文件选择。 */
private data class PreparedPackUpgrade(
    val manifest: PackManifest,
    val ownership: PackOwnership,
    val plan: PackUpgradePlan,
    val selection: Set<String>?,
)
