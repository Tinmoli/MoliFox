// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责原版游戏和模组加载器的安装维护。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.net.URLEncoder
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.exists
import kotlin.io.path.useLines

internal fun encoded(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

/** 版本列表源：official 使用 Mojang 官方清单，bmclapi 使用国内镜像；独立于文件下载源。 */
internal fun versionManifestUrl(source: String): String =
    if (source == "bmclapi") {
        "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
    } else {
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

/** 本启动器生成的版本配置标记：扫描器据此把目录当作受管实例（可修复、可升级加载器）。 */
internal const val MANAGED_PROFILE_MARKER = "molifoxManaged"

/**
 * 原版游戏与模组加载器安装的对外入口。
 *
 * 具体工作拆分到同包的 [MinecraftVersionCatalog]、[InstanceProvisioner]、[MinecraftInstallPipeline]、
 * [LoaderProfileFetcher]、[InstanceProfilePublisher] 与 [LoaderUpgradeService]；公开签名保持不变。
 */
class MinecraftInstaller(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val java: JavaService,
    // official 端点可注入以便测试；bmclapi 分支固定使用镜像地址。
    private val manifestUrl: String = versionManifestUrl("official"),
    private val assetBaseUrl: String = "https://resources.download.minecraft.net",
    // 加载器元数据端点同样可注入：安装流程会先合成完整配置再落盘，离线测试用本地服务验证这条路径。
    private val fabricMetaBase: String = "https://meta.fabricmc.net",
    private val quiltMetaBase: String = "https://meta.quiltmc.org",
    private val legacyFabricMetaBase: String = "https://meta.legacyfabric.net",
    // Forge 系加载器列表用的 Maven 根地址；默认就是官方地址，测试可指向本地服务。
    private val forgeMavenBase: String = "https://maven.minecraftforge.net",
    private val neoforgeMavenBase: String = "https://maven.neoforged.net",
) {
    private val catalog =
        MinecraftVersionCatalog(
            store = store,
            network = network,
            manifestUrl = manifestUrl,
            fabricMetaBase = fabricMetaBase,
            quiltMetaBase = quiltMetaBase,
            legacyFabricMetaBase = legacyFabricMetaBase,
            forgeMavenBase = forgeMavenBase,
            neoforgeMavenBase = neoforgeMavenBase,
        )
    private val provisioner = InstanceProvisioner(store)
    private val loaders =
        LoaderProfileFetcher(
            store = store,
            network = network,
            java = java,
            fabricMetaBase = fabricMetaBase,
            quiltMetaBase = quiltMetaBase,
            legacyFabricMetaBase = legacyFabricMetaBase,
        )
    private val publisher = InstanceProfilePublisher(store, network, catalog)
    private val pipeline =
        MinecraftInstallPipeline(
            store = store,
            network = network,
            catalog = catalog,
            loaders = loaders,
            provisioner = provisioner,
            publisher = publisher,
            assetBaseUrl = assetBaseUrl,
        )
    private val externalUpgrades =
        ExternalLoaderUpgradeService(
            store = store,
            loaders = loaders,
            pipeline = pipeline,
        )
    private val upgrades =
        LoaderUpgradeService(
            store = store,
            network = network,
            catalog = catalog,
            loaders = loaders,
            publisher = publisher,
            pipeline = pipeline,
            externalUpgrades = externalUpgrades,
        )
    private val launcherProfiles = LaunchProfileResolver(store, pipeline)

    suspend fun versions(refresh: Boolean = false): List<ReleaseVersion> = catalog.versions(refresh)

    suspend fun loaderVersions(
        loader: LoaderType,
        gameVersion: String,
    ): List<LoaderVersion> = catalog.loaderVersions(loader, gameVersion)

    /** 受限加载器支持的游戏版本；null 表示不限制。 */
    suspend fun supportedGameVersions(loader: LoaderType): Set<String>? = catalog.supportedGameVersions(loader)

    fun validate(request: InstallRequest) {
        provisioner.validate(request)
    }

    @Synchronized
    fun createInstance(
        request: InstallRequest,
        gameDirectoryId: String? = store.data.activeGameDirectoryId,
    ): GameInstance = provisioner.createInstance(request, gameDirectoryId)

    suspend fun install(
        instanceId: String,
        context: JobContext,
        freshInstall: Boolean = false,
        progressEnd: Float = 1f,
        discardOnFailure: Boolean = true,
        refreshAfterInstall: Boolean = true,
    ) {
        pipeline.install(
            instanceId,
            context,
            freshInstall,
            progressEnd,
            discardOnFailure,
            refreshAfterInstall,
        )
    }

    suspend fun upgradeLoader(
        instanceId: String,
        loaderVersion: String,
        context: JobContext,
    ) {
        upgrades.upgradeLoader(instanceId, loaderVersion, context)
    }

    fun externalLoaderUpgradeBlocker(instance: GameInstance): String? =
        externalUpgrades.externalLoaderUpgradeBlocker(instance)

    suspend fun resolveForLaunch(
        instance: GameInstance,
        context: JobContext,
    ): ResolvedProfile = launcherProfiles.resolveForLaunch(instance, context)

    suspend fun installDependencies(
        profile: ResolvedProfile,
        context: JobContext,
        root: Path = store.minecraftRoot,
        from: Float = 0f,
        to: Float = 1f,
        verifyHashes: Boolean = true,
    ) {
        pipeline.installDependencies(profile, context, root, from, to, verifyHashes)
    }

    internal suspend fun clientJarUrl(
        profile: ResolvedProfile,
        root: Path,
    ): String = publisher.clientJarUrl(profile, root)
}

internal suspend fun runJavaProcess(
    java: String,
    arguments: List<String>,
    directory: Path,
    onLine: (String) -> Unit,
): Unit =
    coroutineScope {
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
                withTimeout(
                    20 * 60 * 1000L,
                ) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
            output.join()
            check(code == 0) { I18n.t("miscA2.installer.processorFailed", code) }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.cancel()
        }
    }

/** 组件的输出文件已存在且哈希一致时直接复用，避免重复跑 processor。 */
internal fun isFreshOutput(
    file: String,
    hash: String,
): Boolean {
    val path = Path.of(file)
    val validHash = hash.matches(Regex("[0-9a-fA-F]{40}"))
    return path.exists() && validHash && digest(path).equals(hash, true)
}
