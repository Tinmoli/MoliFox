// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责扫描游戏目录里的版本目录并识别加载器、隔离方式与安装状态。

package dev.molifox.core

import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** 只读扫描：不写用户目录，识别失败的版本记录原因后跳过。 */
internal class GameDirectoryScanner(
    private val store: LauncherStore,
) {
    fun scan(directory: GameDirectory): List<GameInstance> {
        val root = Path.of(directory.path)
        if (!root.resolve("versions").isDirectory()) return emptyList()
        val resolver = VersionResolver(root, store.platform)
        return root
            .resolve("versions")
            .listDirectoryEntries()
            .filter { it.isDirectory() && !Files.isSymbolicLink(it) }
            .mapNotNull { folder -> scannedInstance(folder, root, resolver, directory) }
    }

    /** 单个版本目录：识别失败只写日志并跳过，不影响同一目录里的其它版本。 */
    private fun scannedInstance(
        folder: Path,
        root: Path,
        resolver: VersionResolver,
        directory: GameDirectory,
    ): GameInstance? =
        runCatching { scannedProfile(folder, root, resolver, directory) }
            .onFailure { store.log("跳过无法识别的版本 ${folder.name}：${it.message}", true) }
            .getOrNull()

    private fun scannedProfile(
        folder: Path,
        root: Path,
        resolver: VersionResolver,
        directory: GameDirectory,
    ): GameInstance {
        val profile = resolver.resolve(folder.name)
        require(profile.mainClass.isNotBlank())
        val libraries = resolver.libraries(profile)
        val coordinate = loaderCoordinate(libraries)
        return GameInstance(
            name = folder.name,
            gameVersion = resolver.minecraftVersion(profile),
            versionId = folder.name,
            loader = loaderType(coordinate),
            loaderVersion = coordinate?.split(':')?.getOrNull(2).orEmpty(),
            gameDirectoryId = directory.id,
            isolation =
                if (hasOwnData(folder)) VersionIsolation.Enabled else VersionIsolation.Inherit,
            externalProfile = true,
            installed = isInstalled(root, profile, resolver, libraries),
        )
    }

    /** 版本依赖里的加载器坐标（Fabric/Quilt/NeoForge/Forge），没有则为 null。 */
    private fun loaderCoordinate(libraries: List<JsonObject>): String? =
        libraries
            .map { it.string("name") }
            .firstOrNull {
                it.startsWith("net.fabricmc:fabric-loader:") ||
                    it.startsWith("org.quiltmc:quilt-loader:") ||
                    it.startsWith("net.neoforged:neoforge:") ||
                    it.startsWith("net.minecraftforge:forge:")
            }

    private fun loaderType(coordinate: String?): LoaderType =
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
    private fun hasOwnData(folder: Path): Boolean =
        listOf("mods", "saves", "config", "options.txt").any { folder.resolve(it).exists() }

    private fun isInstalled(
        root: Path,
        profile: ResolvedProfile,
        resolver: VersionResolver,
        libraries: List<JsonObject>,
    ): Boolean =
        root
            .resolve("versions/${profile.jarId}/${profile.jarId}.jar")
            .isRegularFile() &&
            libraries
                .filter { resolver.hasClasspathArtifact(it) }
                .all { resolver.libraryPath(it).isRegularFile() }
}
