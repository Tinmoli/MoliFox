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

    fun add(source: Path): Int {
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
        val existing =
            store.data.gameDirectories.find {
                runCatching { Path.of(it.path).toRealPath() == root }.getOrDefault(false)
            }
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
            data.copy(
                gameDirectories =
                    if (existing == null) data.gameDirectories + directory
                    else data.gameDirectories,
                instances =
                    data.instances.filter { it.gameDirectoryId != directory.id } +
                        refreshed +
                        fresh,
                activeGameDirectoryId = directory.id,
                selectedInstanceId =
                    data.selectedInstanceId?.takeIf { id ->
                        (refreshed + fresh).any { it.id == id }
                    } ?: (refreshed + fresh).firstOrNull()?.id,
            )
        }
        store.notify("已选择游戏目录，识别到 ${instances.size} 个版本")
        return instances.size
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
                    val isolatedData =
                        listOf("mods", "saves", "config", "options.txt").any {
                            folder.resolve(it).exists()
                        }
                    val sharedData =
                        listOf("mods", "saves", "config", "options.txt").any {
                            root.resolve(it).exists()
                        }
                    GameInstance(
                        name = folder.name,
                        gameVersion = resolver.minecraftVersion(profile),
                        versionId = folder.name,
                        loader = loader,
                        loaderVersion = coordinate?.split(':')?.getOrNull(2).orEmpty(),
                        gameDirectoryId = directory.id,
                        isolation =
                            if (isolatedData) VersionIsolation.Enabled
                            else if (sharedData) VersionIsolation.Disabled
                            else VersionIsolation.Inherit,
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
