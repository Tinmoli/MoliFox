package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/** Register standard Minecraft roots by reference; shared assets and user data stay in place. */
class GameDirectoryService(private val store: LauncherStore) {
    /** Repair metadata saved by older builds without rescanning/removing or copying instances. */
    fun refreshVersionMetadata() {
        val versions =
            store.data.instances
                .filter { it.externalProfile }
                .mapNotNull { instance ->
                    runCatching {
                        val resolver =
                            VersionResolver(store.minecraftRoot(instance), store.platform)
                        instance.id to
                            resolver.minecraftVersion(resolver.resolve(instance.versionId))
                    }
                        .getOrNull()
                }
                .toMap()
        if (
            store.data.instances.any {
                versions[it.id]?.let { version -> version != it.gameVersion } == true
            }
        ) {
            store.mutate { data ->
                data.copy(
                    instances =
                        data.instances.map { instance ->
                            versions[instance.id]?.let { instance.copy(gameVersion = it) }
                                ?: instance
                        }
                )
            }
        }
    }

    /** 用户手动选择目录：登记后设为当前目录，空目录也接受（作为后续安装目标）。 */
    fun add(source: Path): Int {
        val instances = register(resolveRoot(source), activate = true)
        store.notify("已选择游戏目录，识别到 $instances 个版本")
        return instances
    }

    /**
     * 启动时自动识别：把启动器自身目录旁/内发现的现有游戏登记进来，复用 [add] 的扫描与实例物化，
     * 因此版本会直接出现在实例列表里，不需要手动「导入」。
     *
     * 只读扫描，不写用户的 `.minecraft`；已登记的目录不会重复添加，也不改动用户已有的目录与实例
     * 选择（只有本次新登记的目录才会被设为当前目录，等价于手动导入一次）。没有发现时保持安静。
     */
    fun discover(roots: List<StartupRoot>): Int {
        var added: GameDirectory? = null
        for (root in roots) {
            val existing = registered(root.path)
            // 同一目录的版本已作为「MoliFox 默认目录」的受管实例存在时不再登记成外部目录，
            // 否则同一个版本会多出一条实例记录，并把原来的实例挤出当前目录的列表。
            if (existing == null && defaultRootHasInstances(root.path)) {
                store.log(
                    "启动时识别游戏目录 ${root.path}：${root.versions} 个版本（默认目录已登记，跳过）"
                )
                continue
            }
            val instances = register(root.path, activate = false)
            store.log(
                "启动时识别游戏目录 ${root.path}：${root.versions} 个版本" +
                    if (existing == null) "（新登记）" else "（已登记过，仅刷新实例）"
            )
            if (existing == null && added == null && instances > 0) added = registered(root.path)
        }
        // 新发现的目录自动选中，实例列表立刻可见；已登记目录不会抢占用户的选择。
        added?.let { directory ->
            store.mutate { data ->
                data.copy(
                    activeGameDirectoryId = directory.id,
                    selectedInstanceId =
                        data.instances.firstOrNull { it.gameDirectoryId == directory.id }?.id,
                )
            }
        }
        return roots.size
    }

    /** 登记或刷新一个已确认的游戏根；只有 [activate] 时才改动当前目录与选中实例。 */
    private fun register(root: Path, activate: Boolean): Int {
        val existing = registered(root)
        val directory =
            existing
                ?: GameDirectory(
                    name =
                        if (root.fileName.toString() == ".minecraft")
                            root.parent?.fileName?.toString() ?: ".minecraft"
                        else root.fileName.toString(),
                    path = root.toString(),
                )
        val instances = scan(directory)
        store.mutate { data ->
            val current = data.instances.filter { it.gameDirectoryId == directory.id }
            val fresh = instances.filter { candidate ->
                current.none { it.versionId == candidate.versionId }
            }
            val refreshed = current.mapNotNull { original ->
                if (original.managedGameDirectory && !original.externalProfile)
                    return@mapNotNull original
                instances
                    .find { it.versionId == original.versionId }
                    ?.let { scanned ->
                        original.copy(
                            installed = scanned.installed,
                            gameVersion = scanned.gameVersion,
                            loader = scanned.loader,
                            loaderVersion = scanned.loaderVersion,
                        )
                    }
            }
            val candidates = refreshed + fresh
            data.copy(
                gameDirectories =
                    if (existing == null) data.gameDirectories + directory
                    else data.gameDirectories,
                instances =
                    data.instances.filter { it.gameDirectoryId != directory.id } + candidates,
                activeGameDirectoryId = if (activate) directory.id else data.activeGameDirectoryId,
                selectedInstanceId =
                    if (activate)
                        data.selectedInstanceId?.takeIf { id -> candidates.any { it.id == id } }
                            ?: candidates.firstOrNull()?.id
                    else data.selectedInstanceId,
            )
        }
        return instances.size
    }

    /** 用户手动选择：含 versions 的目录、含 .minecraft 的上级目录，或空目录（作为安装目标）。 */
    private fun resolveRoot(source: Path): Path {
        val sourcePath = source.toAbsolutePath().normalize()
        val root =
            when {
                sourcePath.resolve("versions").isDirectory() -> sourcePath
                sourcePath.resolve(".minecraft/versions").isDirectory() ->
                    sourcePath.resolve(".minecraft")
                sourcePath.resolve(".minecraft").isDirectory() -> sourcePath.resolve(".minecraft")
                sourcePath.isDirectory() -> sourcePath
                else -> error("请选择包含 versions 的 Minecraft 目录，或其上一级文件夹")
            }.toRealPath()
        require(root.parent != null) { "请选择专用的游戏文件夹，而非磁盘根目录" }
        return root
    }

    /** 幂等键：真实路径优先，其次规范化绝对路径；Windows 下按大小写不敏感比较。 */
    private fun registered(root: Path): GameDirectory? =
        store.data.gameDirectories.find { sameDirectory(Path.of(it.path), root) }

    /** 该根目录是否已经以「MoliFox 默认目录」的受管实例存在（默认目录的实例不记录目录 id）。 */
    private fun defaultRootHasInstances(root: Path): Boolean {
        val shared = runCatching { store.minecraftRoot }.getOrNull() ?: return false
        return store.data.instances.any { it.gameDirectoryId == null } &&
            sameDirectory(shared, root)
    }

    private fun sameDirectory(left: Path, right: Path): Boolean {
        val a = runCatching { left.toRealPath() }.getOrElse { left.toAbsolutePath().normalize() }
        val b = runCatching { right.toRealPath() }.getOrElse { right.toAbsolutePath().normalize() }
        return if (store.platform.osName == "windows")
            a.toString().equals(b.toString(), ignoreCase = true)
        else a == b
    }

    fun select(id: String?) {
        require(id == null || store.data.gameDirectories.any { it.id == id }) { "找不到游戏目录" }
        store.mutate { data ->
            data.copy(
                activeGameDirectoryId = id,
                selectedInstanceId = data.instances.firstOrNull { it.gameDirectoryId == id }?.id,
            )
        }
    }

    fun refresh(): Int {
        val directory =
            store.data.gameDirectories.find { it.id == store.data.activeGameDirectoryId }
                ?: return 0
        return add(Path.of(directory.path))
    }

    fun remove(id: String) {
        store.mutate { data ->
            val instances = data.instances.filter { it.gameDirectoryId != id }
            val activeDirectory =
                if (data.activeGameDirectoryId == id) null else data.activeGameDirectoryId
            data.copy(
                gameDirectories = data.gameDirectories.filter { it.id != id },
                instances = instances,
                activeGameDirectoryId = activeDirectory,
                selectedInstanceId =
                    data.selectedInstanceId?.takeIf { selected ->
                        instances.any { it.id == selected && it.gameDirectoryId == activeDirectory }
                    } ?: instances.firstOrNull { it.gameDirectoryId == activeDirectory }?.id,
            )
        }
        store.notify("已移除目录记录，游戏文件仍在原目录")
    }

    private fun scan(directory: GameDirectory): List<GameInstance> {
        val root = Path.of(directory.path)
        if (!root.resolve("versions").isDirectory()) return emptyList()
        val resolver = VersionResolver(root, store.platform)
        return root
            .resolve("versions")
            .listDirectoryEntries()
            .filter { it.isDirectory() && !Files.isSymbolicLink(it) }
            .mapNotNull { folder ->
                runCatching {
                    val profile = resolver.resolve(folder.name)
                    require(profile.mainClass.isNotBlank())
                    val libraries = resolver.libraries(profile)
                    val coordinate =
                        libraries
                            .map { it.string("name") }
                            .firstOrNull {
                                it.startsWith("net.fabricmc:fabric-loader:") ||
                                    it.startsWith("org.quiltmc:quilt-loader:") ||
                                    it.startsWith("net.neoforged:neoforge:") ||
                                    it.startsWith("net.minecraftforge:forge:")
                            }
                    val loader =
                        when {
                            coordinate?.contains("fabric-loader") == true -> LoaderType.Fabric
                            coordinate?.contains("quilt-loader") == true -> LoaderType.Quilt
                            coordinate?.contains("neoforge") == true -> LoaderType.NeoForge
                            coordinate != null -> LoaderType.Forge
                            else -> LoaderType.Vanilla
                        }
                    // 只有版本目录里确实存在自己的 mods/saves/config/options.txt 才认定「版本隔离」：
                    // 那个目录本来就是它的独立目录。反之只能记为「继承全局设置」，不能因为游戏根上
                    // 有共用的 mods/saves/options.txt 就把新发现的版本硬钉成「共用游戏目录」——
                    // 全局默认是版本隔离时，那样会让刚下载/刚识别的版本直接落在游戏根上。
                    // 已登记过的实例由 register() 保留原值，不会被这里重写。
                    val ownData =
                        listOf("mods", "saves", "config", "options.txt").any {
                            folder.resolve(it).exists()
                        }
                    GameInstance(
                        name = folder.name,
                        gameVersion = resolver.minecraftVersion(profile),
                        versionId = folder.name,
                        loader = loader,
                        loaderVersion = coordinate?.split(':')?.getOrNull(2).orEmpty(),
                        gameDirectoryId = directory.id,
                        isolation =
                            if (ownData) VersionIsolation.Enabled else VersionIsolation.Inherit,
                        externalProfile = true,
                        installed =
                            root
                                .resolve("versions/${profile.jarId}/${profile.jarId}.jar")
                                .isRegularFile() &&
                                libraries
                                    .filter { resolver.hasClasspathArtifact(it) }
                                    .all { resolver.libraryPath(it).isRegularFile() },
                    )
                }
                    .onFailure { store.log("跳过无法识别的版本 ${folder.name}：${it.message}", true) }
                    .getOrNull()
            }
    }
}
