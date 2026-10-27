package dev.molifox.core

import java.io.IOException
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.jar.JarFile
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.*

internal fun encoded(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

/** 版本列表源：official 使用 Mojang 官方清单，bmclapi 使用国内镜像；独立于文件下载源。 */
internal fun versionManifestUrl(source: String): String =
    if (source == "bmclapi") "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    else "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

class MinecraftInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val java: JavaService,
    // official 端点可注入以便测试；bmclapi 分支固定使用镜像地址。
    private val manifestUrl: String = versionManifestUrl("official"),
    private val assetBaseUrl: String = "https://resources.download.minecraft.net",
) {
    @Volatile private var versionsCache: List<ReleaseVersion> = emptyList()

    suspend fun versions(refresh: Boolean = false): List<ReleaseVersion> {
        if (!refresh && versionsCache.isNotEmpty()) return versionsCache
        val cache = store.metadataDirectory.resolve("versions.json")
        val json =
            try {
                network
                    .json(
                        if (store.data.settings.versionListSource == "bmclapi")
                            versionManifestUrl("bmclapi")
                        else manifestUrl,
                        sourceOverride = store.data.settings.versionListSource,
                    )
                    .asJsonObject
                    .also { writeJson(cache, it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (cache.exists()) {
                    store.log("网络不可用，使用缓存的游戏版本列表")
                    readObject(cache)
                } else throw error
            }
        return json
            .array("versions")
            .map { gson.fromJson(it, ReleaseVersion::class.java) }
            .also { versionsCache = it }
    }

    suspend fun loaderVersions(loader: LoaderType, gameVersion: String): List<LoaderVersion> {
        safeFilename(gameVersion)
        return when (loader) {
            LoaderType.Vanilla -> emptyList()
            LoaderType.Fabric,
            LoaderType.Quilt -> {
                val host =
                    if (loader == LoaderType.Fabric) "https://meta.fabricmc.net"
                    else "https://meta.quiltmc.org"
                val apiVersion = if (loader == LoaderType.Fabric) "v2" else "v3"
                network
                    .json("$host/$apiVersion/versions/loader/${encoded(gameVersion)}")
                    .asJsonArray
                    .map { item ->
                        val info = item.asJsonObject.obj("loader")
                        LoaderVersion(
                            info.string("version"),
                            info.boolean(
                                "stable",
                                !Regex("(?i)alpha|beta|rc|snapshot")
                                    .containsMatchIn(info.string("version")),
                            ),
                        )
                    }
                    .sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
            LoaderType.Forge -> {
                val xml =
                    network.text(
                        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
                    )
                Regex("<version>([^<]+)</version>")
                    .findAll(xml)
                    .map { it.groupValues[1] }
                    .filter { it.startsWith("$gameVersion-") }
                    .map { LoaderVersion(it.removePrefix("$gameVersion-")) }
                    .toList()
                    .sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
            LoaderType.NeoForge -> {
                val legacy = gameVersion == "1.20.1"
                val project = if (legacy) "forge" else "neoforge"
                val parts = gameVersion.split('.')
                val prefix =
                    if (legacy) "1.20.1-"
                    else if (parts.first() == "1")
                        "${parts.getOrElse(1) { "0" }}.${parts.getOrElse(2) { "0" }}."
                    else "${parts[0]}.${parts.getOrElse(1) { "0" }}."
                network
                    .json(
                        "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/$project"
                    )
                    .asJsonObject
                    .array("versions")
                    .map { it.asString }
                    .filter { it.startsWith(prefix) }
                    .map { LoaderVersion(it, !it.contains("beta") && !it.contains("alpha")) }
                    .sortedWith { left, right ->
                        compareLoaderVersions(right.version, left.version)
                    }
            }
        }
    }

    fun validate(request: InstallRequest) {
        require(request.name.isNotBlank() && request.name.length <= 80) { "实例名称需要 1–80 个字符" }
        safeFilename(request.gameVersion)
        if (request.loader != LoaderType.Vanilla) {
            require(request.loaderVersion.isNotBlank()) { "请选择加载器版本" }
            safeFilename(request.loaderVersion)
        }
    }

    fun createInstance(
        request: InstallRequest,
        gameDirectoryId: String? = store.data.activeGameDirectoryId,
    ): GameInstance {
        validate(request)
        val instance =
            GameInstance(
                name = request.name.trim(),
                gameVersion = request.gameVersion,
                versionId = request.gameVersion,
                loader = request.loader,
                loaderVersion = request.loaderVersion,
                gameDirectoryId = gameDirectoryId,
                managedGameDirectory = true,
            )
        store.mutate {
            it.copy(instances = it.instances + instance, selectedInstanceId = instance.id, activeGameDirectoryId = gameDirectoryId)
        }
        store.instancePath(instance.id).createDirectories()
        return instance
    }

    suspend fun install(instanceId: String, context: JobContext) {
        var instance = store.instance(instanceId)
        val root = store.minecraftRoot(instance)
        store.updateInstance(instanceId) { it.copy(installed = false) }
        context.progress(0f, "获取 Minecraft 元数据…")
        if (instance.externalProfile) {
            val resolved = VersionResolver(root, store.platform).resolve(instance.versionId)
            installDependencies(resolved, context, root)
        } else {
            val metadata = versions().find { it.id == instance.gameVersion } ?: error("此游戏版本不存在")
            require(metadata.type !in setOf("old_alpha", "old_beta")) {
                "当前支持 Minecraft 1.6 及以后的版本，早期版本仍需适配"
            }
            val jsonPath = root.resolve("versions/${safeFilename(metadata.id)}/${metadata.id}.json")
            network.download(DownloadArtifact(metadata.url, jsonPath, metadata.sha1.orEmpty()))
            val base = VersionResolver(root, store.platform).resolve(metadata.id)
            require(base.mainClass.isNotBlank()) { "此游戏版本尚未支持" }
            installDependencies(base, context, root)
            context.checkCancelled()
            val versionId =
                when (instance.loader) {
                    LoaderType.Vanilla -> metadata.id
                    LoaderType.Fabric,
                    LoaderType.Quilt -> installFabricProfile(instance)
                    LoaderType.Forge,
                    LoaderType.NeoForge -> installForge(instance, base, context)
                }
            store.updateInstance(instanceId) { it.copy(versionId = versionId) }
            if (instance.loader != LoaderType.Vanilla)
                installDependencies(
                    VersionResolver(root, store.platform).resolve(versionId),
                    context,
                    root,
                )
        }
        context.checkCancelled()
        store.updateInstance(instanceId) { it.copy(installed = true) }
        context.progress(1f, "安装完成")
    }

    /** 为已安装的实例更换或重装模组加载器；沿用完整安装流程校验并补齐依赖，失败回滚记录。 */
    suspend fun changeLoader(
        instanceId: String,
        loader: LoaderType,
        loaderVersion: String,
        context: JobContext,
    ) {
        val previous = store.instance(instanceId)
        require(previous.installed) { "请先完成游戏安装，再更改加载器" }
        require(!previous.externalProfile) { "外部实例的加载器请由原目录维护，避免覆盖自定义启动配置" }
        require(previous.packFile.isBlank()) { "整合包的加载器由包清单定义，暂不支持直接更换" }
        if (
            previous.loader == loader &&
                previous.loaderVersion == (if (loader == LoaderType.Vanilla) "" else loaderVersion)
        )
            return
        if (loader != LoaderType.Vanilla) {
            require(loaderVersion.isNotBlank()) { "请选择加载器版本" }
            safeFilename(loaderVersion)
        }
        store.updateInstance(instanceId) {
            it.copy(loader = loader, loaderVersion = loaderVersion, installed = false)
        }
        try {
            install(instanceId, context)
            store.updateInstance(instanceId) {
                it.copy(previousComponents = previous.componentSelection())
            }
        } catch (failure: Exception) {
            store.updateInstance(instanceId) {
                it.copy(
                    loader = previous.loader,
                    loaderVersion = previous.loaderVersion,
                    versionId = previous.versionId,
                    installed = previous.installed,
                )
            }
            throw failure
        }
    }

    /** Restore a known profile after checking its actual client, libraries and assets. */
    suspend fun restoreComponents(instanceId: String, context: JobContext) {
        val current = store.instance(instanceId)
        require(current.installed && !current.externalProfile && current.packFile.isBlank()) {
            "此实例当前不支持组件回退"
        }
        val previous = requireNotNull(current.previousComponents) { "没有可回退的组件记录" }
        context.progress(0f, "校验上一组件及其依赖…")
        val root = store.minecraftRoot(current)
        val profile = VersionResolver(root, store.platform).resolve(previous.versionId)
        installDependencies(profile, context, root)
        context.checkCancelled()
        store.updateInstance(instanceId) {
            it.copy(
                loader = previous.loader,
                loaderVersion = previous.loaderVersion,
                versionId = previous.versionId,
                previousComponents = current.componentSelection(),
            )
        }
        context.progress(1f, "已恢复 ${previous.title}")
    }

    suspend fun resolveForLaunch(instance: GameInstance, context: JobContext): ResolvedProfile {
        val resolver = VersionResolver(store.minecraftRoot(instance), store.platform)
        try {
            return resolver.resolve(instance.versionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (instance.externalProfile)
                throw IOException("外部版本配置缺失或损坏，请修复原版本：${instance.versionId}", error)
            context.progress(0f, "恢复游戏版本配置…")
            install(instance.id, context)
            return resolver.resolve(store.instance(instance.id).versionId)
        }
    }

    suspend fun installDependencies(
        profile: ResolvedProfile,
        context: JobContext,
        root: Path = store.minecraftRoot,
        from: Float = 0f,
        to: Float = 1f,
    ) {
        fun at(fraction: Float) = from + (to - from) * fraction
        val verifier = GameFileVerifier(network, store.data.settings.maxConcurrent)
        context.progress(at(.02f), "检查游戏文件…")
        val resolver = VersionResolver(root, store.platform)
        val artifacts =
            resolver
                .libraries(profile)
                .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
                .toMutableList()
        val client = profile.json.obj("downloads").obj("client")
        artifacts +=
            DownloadArtifact(
                client.string("url"),
                root.resolve("versions/${profile.jarId}/${profile.jarId}.jar"),
                client.string("sha1"),
                client.get("size")?.asLong ?: 0,
            )
        val logging = profile.json.obj("logging").obj("client").obj("file")
        if (logging.string("url").isNotBlank())
            artifacts +=
                DownloadArtifact(
                    logging.string("url"),
                    safePath(root.resolve("assets/log_configs"), logging.string("id")),
                    logging.string("sha1"),
                )
        verifier.ensure(
            artifacts,
            context,
            "补全游戏与依赖",
            at(.05f),
            at(.5f),
        )
        val index = profile.json.obj("assetIndex")
        if (index.string("url").isNotBlank()) {
            val indexPath = safePath(root.resolve("assets/indexes"), "${index.string("id")}.json")
            verifier.ensure(
                listOf(
                    DownloadArtifact(
                        index.string("url"),
                        indexPath,
                        index.string("sha1"),
                        index.get("size")?.asLong ?: 0,
                    )
                ),
                context,
                "补全资源索引",
                at(.49f),
                at(.5f),
            )
            val objects = readObject(indexPath).obj("objects")
            val assets =
                objects.entrySet().map { (_, value) ->
                    val item = value.asJsonObject
                    val hash = item.string("hash")
                    require(hash.matches(Regex("[0-9a-fA-F]{40}"))) { "资源哈希无效" }
                    DownloadArtifact(
                        "$assetBaseUrl/${hash.take(2)}/$hash",
                        root.resolve("assets/objects/${hash.take(2)}/$hash"),
                        hash,
                        item.get("size")?.asLong ?: 0,
                    )
                }
            verifier.ensure(
                assets,
                context,
                "补全游戏资源",
                at(.5f),
                at(.98f),
            )
        }
    }

    private suspend fun installFabricProfile(instance: GameInstance): String {
        val root = store.minecraftRoot(instance)
        val host =
            if (instance.loader == LoaderType.Fabric) "https://meta.fabricmc.net"
            else "https://meta.quiltmc.org"
        val apiVersion = if (instance.loader == LoaderType.Fabric) "v2" else "v3"
        val profile =
            network
                .json(
                    "$host/$apiVersion/versions/loader/${encoded(instance.gameVersion)}/${encoded(instance.loaderVersion)}/profile/json"
                )
                .asJsonObject
        val id =
            "molifox-${instance.loader.name.lowercase()}-${instance.gameVersion}-${instance.loaderVersion}"
        safeFilename(id)
        profile.addProperty("id", id)
        writeJson(root.resolve("versions/$id/$id.json"), profile)
        return id
    }

    private suspend fun installForge(
        instance: GameInstance,
        base: ResolvedProfile,
        context: JobContext,
    ): String {
        val root = store.minecraftRoot(instance)
        val isForge = instance.loader == LoaderType.Forge
        val project = if (isForge || instance.gameVersion == "1.20.1") "forge" else "neoforge"
        val version =
            if (isForge) "${instance.gameVersion}-${instance.loaderVersion}"
            else instance.loaderVersion
        val host =
            if (isForge) "https://maven.minecraftforge.net"
            else "https://maven.neoforged.net/releases"
        val group = if (isForge) "net/minecraftforge" else "net/neoforged"
        val url = "$host/$group/$project/$version/$project-$version-installer.jar"
        val archive =
            store.downloadDirectory.resolve(
                "loaders/${safeFilename(project)}-${safeFilename(version)}-installer.jar"
            )
        context.progress(.1f, "下载 ${instance.loader.title} 安装程序…")
        val installerHash = network.text("$url.sha1").trim().substringBefore(' ').trim()
        require(installerHash.matches(Regex("[a-fA-F0-9]{40}"))) { "加载器安装程序缺少有效校验信息" }
        network.download(DownloadArtifact(url, archive, installerHash))
        val runtime =
            java.resolve(
                base.requiredJava,
                instance.javaPath.ifBlank { store.data.settings.javaPath },
                context,
            )
        val temporaryRoot =
            store.temporaryDirectory.resolve("forge-${UUID.randomUUID()}").apply {
                createDirectories()
            }
        try {
            ZipFile(archive.toFile()).use { zip ->
                val profile =
                    zip.getEntry("install_profile.json")?.let {
                        zip.getInputStream(it).reader().use { reader ->
                            com.google.gson.JsonParser.parseReader(reader).asJsonObject
                        }
                    } ?: error("加载器安装包缺少 install_profile.json")
                val versionJson =
                    if (profile.has("versionInfo")) profile.obj("versionInfo")
                    else {
                        val jsonEntry =
                            zip.getEntry(profile.string("json", "/version.json").removePrefix("/"))
                                ?: error("安装包缺少 version.json")
                        zip.getInputStream(jsonEntry).reader().use {
                            com.google.gson.JsonParser.parseReader(it).asJsonObject
                        }
                    }
                val id =
                    "molifox-${instance.loader.name.lowercase()}-${instance.gameVersion}-${instance.loaderVersion}"
                safeFilename(id)
                versionJson.addProperty("id", id)
                if (!versionJson.has("inheritsFrom"))
                    versionJson.addProperty("inheritsFrom", instance.gameVersion)
                // Extract only installer-owned Maven artifacts, never arbitrary archive paths.
                for ((entry, target) in validateArchive(zip, "maven/", root.resolve("libraries"))) {
                    context.checkCancelled()
                    target.parent.createDirectories()
                    zip.getInputStream(entry).use {
                        Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                // Pre-1.13 installers embed the universal JAR outside maven/.
                if (profile.has("versionInfo")) {
                    val embedded =
                        zip.entries().asSequence().firstOrNull {
                            !it.isDirectory &&
                                !it.name.contains('/') &&
                                it.name.endsWith("universal.jar")
                        }
                    val forgeLibrary =
                        versionJson
                            .array("libraries")
                            .map { it.asJsonObject }
                            .find { it.string("name").startsWith("net.minecraftforge:forge:") }
                    if (embedded != null && forgeLibrary != null) {
                        val target =
                            safePath(
                                root.resolve("libraries"),
                                mavenPath(forgeLibrary.string("name")),
                            )
                        target.parent.createDirectories()
                        zip.getInputStream(embedded).use {
                            Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
                val resolver = VersionResolver(root, store.platform)
                val libraries =
                    profile
                        .array("libraries")
                        .map { it.asJsonObject }
                        .filter { resolver.allowed(it) }
                        .flatMap { resolver.libraryArtifacts(it).map { pair -> pair.first } }
                network.downloadAll(
                    libraries,
                    store.data.settings.maxConcurrent,
                    context,
                    "下载加载器安装依赖",
                    .2f,
                    .5f,
                )
                val variables =
                    mutableMapOf(
                        "SIDE" to "client",
                        "ROOT" to root.toString(),
                        "LIBRARY_DIR" to root.resolve("libraries").toString(),
                        "MINECRAFT_JAR" to
                            root.resolve("versions/${base.jarId}/${base.jarId}.jar").toString(),
                        "MINECRAFT_VERSION" to instance.gameVersion,
                        "INSTALLER" to archive.toString(),
                    )
                for ((key, element) in profile.obj("data").entrySet()) {
                    val value = element.asJsonObject.string("client")
                    variables[key] =
                        if (
                            value.startsWith('/') && zip.getEntry(value.removePrefix("/")) != null
                        ) {
                            val entry = zip.getEntry(value.removePrefix("/"))
                            val target = safePath(temporaryRoot, value.removePrefix("/"))
                            target.parent.createDirectories()
                            zip.getInputStream(entry).use {
                                Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                            target.toString()
                        } else value
                }
                fun expand(value: String): String {
                    var result = value
                    repeat(12) {
                        result =
                            Regex("\\{([^}]+)}").replace(result) { match ->
                                variables[match.groupValues[1]] ?: match.value
                            }
                    }
                    if (result.startsWith('[') && result.endsWith(']'))
                        return safePath(
                                root.resolve("libraries"),
                                mavenPath(result.substring(1, result.length - 1)),
                            )
                            .toString()
                    if (result.startsWith('\'') && result.endsWith('\''))
                        result = result.substring(1, result.length - 1)
                    require(!Regex("\\{[^}]+}").containsMatchIn(result)) { "安装器包含无法解析的变量：$result" }
                    return result
                }
                val processors =
                    profile
                        .array("processors")
                        .map { it.asJsonObject }
                        .filter {
                            !it.has("sides") ||
                                it.array("sides").any { side -> side.asString == "client" }
                        }
                for ((index, processor) in processors.withIndex()) {
                    context.checkCancelled()
                    context.progress(
                        .5f + index.toFloat() / maxOf(processors.size, 1) * .45f,
                        "处理加载器组件 ${index + 1}/${processors.size}",
                    )
                    val outputs =
                        processor.obj("outputs").entrySet().map {
                            expand(it.key) to expand(it.value.asString)
                        }
                    if (
                        outputs.isNotEmpty() &&
                            outputs.all { (file, hash) ->
                                Path.of(file).exists() &&
                                    hash.matches(Regex("[0-9a-fA-F]{40}")) &&
                                    digest(Path.of(file)).equals(hash, true)
                            }
                    )
                        continue
                    val artifact =
                        safePath(
                            root.resolve("libraries"),
                            mavenPath(processor.string("jar")),
                        )
                    val mainClass =
                        JarFile(artifact.toFile()).use {
                            it.manifest?.mainAttributes?.getValue("Main-Class")
                        } ?: error("加载器处理器没有主程序")
                    val classpath =
                        (processor.array("classpath").map {
                                safePath(
                                        root.resolve("libraries"),
                                        mavenPath(it.asString),
                                    )
                                    .toString()
                            } + artifact.toString())
                            .joinToString(store.platform.classpathSeparator)
                    runJavaProcess(
                        runtime.path,
                        listOf("-cp", classpath, mainClass) +
                            processor.array("args").map { expand(it.asString) },
                        root,
                    ) { line ->
                        store.log(line)
                    }
                    outputs.forEach { (file, hash) ->
                        require(
                            Path.of(file).exists() &&
                                (!hash.matches(Regex("[0-9a-fA-F]{40}")) ||
                                    digest(Path.of(file)).equals(hash, true))
                        ) {
                            "加载器处理结果校验失败"
                        }
                    }
                }
                writeJson(root.resolve("versions/$id/$id.json"), versionJson)
                return id
            }
        } finally {
            val cacheRoot = store.temporaryDirectory.toAbsolutePath().normalize()
            check(
                temporaryRoot.toAbsolutePath().normalize().parent == cacheRoot &&
                    temporaryRoot.name.startsWith("forge-")
            )
            if (temporaryRoot.exists() && !Files.isSymbolicLink(temporaryRoot)) {
                Files.walk(temporaryRoot).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }
}

internal suspend fun runJavaProcess(
    java: String,
    arguments: List<String>,
    directory: Path,
    onLine: (String) -> Unit,
): Unit = coroutineScope {
    val process =
        ProcessBuilder(listOf(java) + arguments)
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .start()
    val output =
        launch(Dispatchers.IO) {
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        }
    try {
        val code =
            withTimeout(20 * 60 * 1000L) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
        output.join()
        check(code == 0) { "Java 组件处理失败，退出码 $code。详细信息请查看日志" }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        output.cancel()
    }
}
