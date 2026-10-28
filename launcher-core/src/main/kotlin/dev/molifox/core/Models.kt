// 负责账户、实例、配置和任务的公共数据模型。

package dev.molifox.core

import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** 当前启动器版本号；更新检查以此对比清单中的 version 字段。 */
const val LAUNCHER_VERSION = "0.3.0"

enum class LoaderType(val title: String) {
    Vanilla("原版"),
    Fabric("Fabric"),
    Quilt("Quilt"),
    Forge("Forge"),
    NeoForge("NeoForge"),
}

enum class AccountType {
    Offline,
    Microsoft,
    /** 外置认证服务器账户（authlib-injector / Yggdrasil，如 LittleSkin）。 */
    Authlib,
}

enum class VersionIsolation(val title: String) {
    Inherit("继承全局设置"),
    Enabled("版本隔离"),
    Disabled("共用游戏目录"),
}

data class GameDirectory(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val path: String = "",
)

enum class JobStatus {
    Queued,
    Running,
    Completed,
    Failed,
    Cancelled,
}

data class LauncherSettings(
    val gameRoot: String = "",
    /**
     * gameRoot 目录语义：0 = 旧版（实际根目录在 gameRoot 之下的 minecraft/ 一层）， 1 = 直接使用 gameRoot。旧配置首次加载时迁移并置
     * 1，之后不再重复迁移。
     */
    val gameRootSemantics: Int = 0,
    val javaPath: String = "",
    val memoryMb: Int = 4096,
    val autoMemory: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val extraJvmArgs: String = "",
    val advancedLaunch: AdvancedLaunchSettings = AdvancedLaunchSettings(),
    val downloadSource: String = "auto",
    val maxConcurrent: Int = 8,
    val microsoftClientId: String = MoliFoxIdentity.microsoftClientId,
    val darkTheme: Boolean = false,
    val minimizeOnLaunch: Boolean = false,
    val isolateVersions: Boolean = true,
    val initialMemoryMb: Int = 512,
    val fullscreen: Boolean = false,
    val themeMode: String = "",
    val uiScale: Float = 1f,
    val backgroundPath: String = "",
    val backgroundRevision: Long = 0,
    val backgroundOpacity: Float = .7f,
    /** crop / fit / stretch；旧配置保留居中裁切。 */
    val backgroundFit: String = "crop",
    /**
     * topLeft / topCenter / topRight / centerLeft / center / centerRight / bottomLeft /
     * bottomCenter / bottomRight.
     */
    val backgroundAlignment: String = "center",
    val glassEnabled: Boolean = true,
    val surfaceOpacity: Float = .9f,
    val backgroundBlur: Float = 16f,
    val fontChoice: String = "",
    val fontScale: Float = 1f,
    val customFonts: List<LauncherFont> = emptyList(),
    val pages: PagePreferences = PagePreferences(),
    // 页面与外观偏好均提供默认值，兼容缺少这些字段的配置。
    /** 主题颜色：预设键（sakura/ocean/mint/amber/lavender/graphite）或 #RRGGBB 自定义。 */
    val accentColor: String = "sakura",
    /** 关闭后页面切换与提示不使用过渡动画。 */
    val animationsEnabled: Boolean = true,
    /** 标题栏半透明（无需玻璃效果也生效）。 */
    val titlebarTransparent: Boolean = false,
    /** 整窗透明（重启后生效）。 */
    val windowTransparent: Boolean = false,
    /** 界面语言，当前仅提供 zh-CN。 */
    val language: String = "zh-CN",
    /** 启动器更新清单地址；为空表示未配置更新源，检查更新按钮会如实提示。 */
    val updateManifestUrl: String = "",
    /** 启动时自动检查更新（需要已配置更新清单）。 */
    val autoCheckUpdates: Boolean = false,
    /** 版本列表源：official / bmclapi，独立于游戏文件下载源。 */
    val versionListSource: String = "auto",
    /** 附加内容（模组/整合包等）站点：modrinth / curseforge。 */
    val contentDownloadSource: String = "modrinth",
    /** 文件下载缓存文件夹；为空使用数据目录 cache/。 */
    val downloadCachePath: String = "",
    val proxyEnabled: Boolean = false,
    /** 空值沿用旧代理开关；新配置默认跟随系统，支持 system/direct/manual。 */
    val proxyMode: String = "",
    val proxyType: String = "http",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    /** CurseForge 开发者 API Key，由用户自行申请后填入。 */
    val curseForgeApiKey: String = "",
    /** 全局游戏窗口模式：launcher（与启动器一样大）/ fullscreen / custom。 */
    val gameWindowMode: String = "custom",
)

data class Account(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val uuid: String = "",
    val type: AccountType = AccountType.Offline,
    /** 玩家皮肤纹理地址（textures.minecraft.net），离线账户为空。 */
    val skinUrl: String = "",
    /** Launcher-only offline preview, not a skin-server or game authentication override. */
    val localSkinId: String = "",
    val skinVariant: SkinVariant = SkinVariant.Classic,
    /** 外置认证服务器的 API 根地址（例如 https://littleskin.cn/api/yggdrasil）；其它账户类型为空。 */
    val authlibServer: String = "",
)

/**
 * 用户添加过的外置认证服务器。只保存在 launcher.json，不含任何令牌或密码； [url] 是规范化后的 API 根地址，[name] 取自服务器元数据的
 * meta.serverName。
 */
data class AuthlibServer(
    val url: String = "",
    val name: String = "",
    val addedAt: String = Instant.now().toString(),
)

/**
 * 内置的默认外置认证服务器。PCL 的「使用 LittleSkin 登录」与 HMCL 的 `AuthlibInjectorServerList.createDefault()` 都把
 * LittleSkin 作为默认项， 这里同样预置一条，用户可在账户页直接选它登录，也可以删掉或添加别的服务器。
 */
val DEFAULT_AUTHLIB_SERVER =
    AuthlibServer(url = "https://littleskin.cn/api/yggdrasil", name = "LittleSkin")

data class GameInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val iconStyle: InstanceIconStyle = InstanceIconStyle.Auto,
    val iconId: String = "",
    /** Pack-provided artwork is separate from a user's explicit choice. */
    val packIconId: String = "",
    val gameVersion: String = "",
    val versionId: String = "",
    val loader: LoaderType = LoaderType.Vanilla,
    val loaderVersion: String = "",
    val createdAt: String = Instant.now().toString(),
    val lastPlayed: String? = null,
    val playTimeSeconds: Long = 0,
    val installed: Boolean = false,
    val memoryMb: Int = 0,
    val autoMemory: Boolean? = null,
    val javaPath: String = "",
    val externalProfile: Boolean = false,
    val packFile: String = "",
    val gameDirectoryId: String? = null,
    val isolation: VersionIsolation = VersionIsolation.Inherit,
    val initialMemoryMb: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val windowMode: String = "inherit",
    val extraJvmArgs: String = "",
    val notes: String = "",
    val favorite: Boolean = false,
    val managedGameDirectory: Boolean = false,
    val packOptionalFiles: List<String>? = null,
    val advancedLaunch: AdvancedLaunchSettings? = null,
    val previousComponents: ComponentSelection? = null,
)

data class ComponentSelection(
    val loader: LoaderType,
    val loaderVersion: String,
    val versionId: String,
) {
    val title: String
        get() = loader.title + if (loaderVersion.isBlank()) "" else " $loaderVersion"
}

internal fun GameInstance.componentSelection() =
    ComponentSelection(loader, loaderVersion, versionId)

data class LauncherData(
    val schemaVersion: Int = 1,
    val settings: LauncherSettings = LauncherSettings(),
    val accounts: List<Account> = emptyList(),
    val instances: List<GameInstance> = emptyList(),
    val activeAccountId: String? = null,
    val selectedInstanceId: String? = null,
    /** 用户添加过的外置认证服务器列表（仅 URL 与显示名，令牌在凭据库中）。 */
    val authlibServers: List<AuthlibServer> = listOf(DEFAULT_AUTHLIB_SERVER),
    val gameDirectories: List<GameDirectory> = emptyList(),
    val activeGameDirectoryId: String? = null,
)

data class DownloadJob(
    val id: String,
    val title: String,
    val status: JobStatus = JobStatus.Queued,
    val progress: Float = 0f,
    val detail: String = "等待开始",
    val error: String? = null,
)

data class LogLine(
    val time: String = Instant.now().toString(),
    val message: String,
    val isError: Boolean = false,
    val isGame: Boolean = false,
)

data class LauncherState(
    val data: LauncherData,
    val jobs: List<DownloadJob> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val runningInstanceId: String? = null,
    val toast: String? = null,
    val launchIssue: LaunchIssue? = null,
    val gameRun: GameRunEvent? = null,
)

data class ReleaseVersion(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String,
    val sha1: String? = null,
)

data class LoaderVersion(val version: String, val stable: Boolean = true)

data class InstallRequest(
    val name: String,
    val gameVersion: String,
    val loader: LoaderType = LoaderType.Vanilla,
    val loaderVersion: String = "",
)

data class JavaRuntime(
    val path: String,
    val majorVersion: Int,
    val vendor: String,
    val architecture: String = "",
)

data class ModFile(
    val filename: String,
    val size: Long,
    val enabled: Boolean,
    val metadata: ModMetadata = ModMetadata(),
    /** 修改时间（毫秒）：界面按「路径 + 修改时间」缓存模组图标，替换文件后自动重新读取。 */
    val modified: Long = 0,
)

data class ContentHit(
    val id: String,
    val slug: String,
    val title: String,
    val description: String,
    val author: String,
    val downloads: Long,
    val type: String,
    val iconUrl: String?,
)

data class ContentSearch(val hits: List<ContentHit>, val total: Int)

data class DeviceCode(val userCode: String, val verificationUri: String, val expiresAt: Long)

data class GameCredentials(
    val name: String,
    val uuid: String,
    val accessToken: String = "0",
    val userType: String = "legacy",
    /** 档案返回的活跃皮肤纹理地址，离线凭据为空。 */
    val skinUrl: String = "",
    val skinVariant: SkinVariant = SkinVariant.Classic,
    /** 非空表示外置认证服务器（authlib-injector）账户：启动时注入对应的 javaagent 参数。 */
    val authlibServer: String = "",
)

interface PlatformServices {
    val osName: String
    val architecture: String
    val javaExecutable: String
    val classpathSeparator: String
    val totalMemoryMb: Int
    val availableMemoryMb: Int
        get() = totalMemoryMb / 2

    val defaultDataDirectory: Path

    fun openFolder(directory: Path)

    fun openUrl(url: String)

    fun moveToTrash(file: Path)
}
