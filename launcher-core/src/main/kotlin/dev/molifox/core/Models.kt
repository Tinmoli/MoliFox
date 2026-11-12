// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户、实例、配置和任务的公共数据模型。

package dev.molifox.core

import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.jvm.Transient

/** 当前启动器版本号；更新检查以此对比清单中的 version 字段。 */
const val LAUNCHER_VERSION = "0.5.0"

enum class LoaderType {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
    OptiFine,
    LiteLoader,
    LegacyFabric,
    Cleanroom,
    ;

    /** 显示名在读取时翻译，切换语言后重新取值；品牌名保持原样。 */
    val title: String
        get() =
            when (this) {
                Vanilla -> I18n.t("core.loaderType.vanilla")
                LegacyFabric -> "Legacy Fabric"
                else -> name
            }
}

enum class AccountType {
    Offline,
    Microsoft,

    /** 外置认证服务器账户（authlib-injector / Yggdrasil，如 LittleSkin）。 */
    Authlib,
}

enum class VersionIsolation {
    Inherit,
    Enabled,
    Disabled,
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() =
            when (this) {
                Inherit -> I18n.t("core.versionIsolation.inherit")
                Enabled -> I18n.t("core.versionIsolation.enabled")
                Disabled -> I18n.t("core.versionIsolation.disabled")
            }
}

/**
 * 有效隔离值的来源：用户显式选择（版本隔离 / 共用游戏目录）、目录证据推断、或全局默认。
 * 推断值不能冒充用户选择：界面与日志都要说明来源，且显式选择永远优先于推断。
 */
enum class IsolationSource {
    Explicit,
    Inferred,
    GlobalDefault,
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() =
            when (this) {
                Explicit -> I18n.t("core.isolationSource.explicit")
                Inferred -> I18n.t("core.isolationSource.inferred")
                GlobalDefault -> I18n.t("core.isolationSource.globalDefault")
            }
}

/** 启动日志里的来源名称：显式设置读作「实例设置」，与推断、全局默认并列。 */
val IsolationSource.logTitle: String
    get() =
        when (this) {
            IsolationSource.Explicit -> I18n.t("core.isolationSource.logExplicit")
            IsolationSource.Inferred -> I18n.t("core.isolationSource.inferred")
            IsolationSource.GlobalDefault -> I18n.t("core.isolationSource.globalDefault")
        }

/**
 * 游戏进程优先级：界面四档，Windows 上分别对应 BelowNormal / Normal / AboveNormal / High。
 * 不使用 RealTime，避免游戏抢占系统输入与音频线程。
 */
@JsonAdapter(ProcessPriorityAdapter::class)
enum class ProcessPriority {
    Low,
    Normal,
    High,
    VeryHigh,
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() =
            when (this) {
                Low -> I18n.t("core.processPriority.low")
                Normal -> I18n.t("core.processPriority.normal")
                High -> I18n.t("core.processPriority.high")
                VeryHigh -> I18n.t("core.processPriority.veryHigh")
            }
}

/** 配置里出现未知优先级（手改配置、旧版本写入）时按 Normal 读入，绝不让整份配置读取失败。 */
class ProcessPriorityAdapter : TypeAdapter<ProcessPriority>() {
    override fun write(
        out: JsonWriter,
        value: ProcessPriority?,
    ) {
        if (value == null) out.nullValue() else out.value(value.name)
    }

    override fun read(reader: JsonReader): ProcessPriority {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ProcessPriority.Normal
        }
        val raw = reader.nextString()
        return ProcessPriority.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: ProcessPriority.Normal
    }
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

/**
 * 后台任务的稳定类型：界面按类型决定图标与操作，不解析可变而且会随语言变化的任务标题。
 * 新增任务时按语义选择；无法归类的用 [Generic]。
 */
enum class LauncherTaskKind {
    Generic,
    InstallInstance,
    RepairInstance,
    InstallContent,
    ImportContent,
    PrepareContent,
    ExportContent,
    UpdateContent,
    UpdateLauncher,
    InstallJava,
    LaunchGame,
    SaveOperation,
    CopyInstance,
    ImportPacks,
    MoveInstance,
    DiscoverDirectory,
    UpgradeLoader,
    UpgradeVersion,
}

data class LauncherSettings(
    val gameRoot: String = "",
    val javaPath: String = "",
    /** 用户手动「添加」过的 Java 可执行文件；检测时与系统 Java 一起列出，删除前一直保留。 */
    val customJavaPaths: List<String> = emptyList(),
    /** Java 下载来源：自动测速，或仅使用 Azul / Adoptium。 */
    val javaDownloadSource: String = "auto",
    val memoryMb: Int = 4096,
    val autoMemory: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val extraJvmArgs: String = DEFAULT_EXTRA_JVM_ARGS,
    /** GC 策略只保存选择，启动时按 Java 版本生成参数，不写进附加 JVM 参数。 */
    val gcPreset: String = GcPreset.TunedG1.value,
    val advancedLaunch: AdvancedLaunchSettings = AdvancedLaunchSettings(),
    val downloadSource: String = "auto",
    val downloadSegments: Int = DEFAULT_DOWNLOAD_SEGMENTS,
    val networkTimeoutSeconds: Int = 45,
    val microsoftClientId: String = MoliFoxIdentity.MICROSOFT_CLIENT_ID,
    val isolateVersions: Boolean = true,
    val initialMemoryMb: Int = 512,
    val fullscreen: Boolean = false,
    val themeMode: String = "system",
    val uiScale: Float = 1f,
    val backgroundPath: String = "",
    val backgroundRevision: Long = 0,
    /** null 沿用旧通用背景；显式 default/none 分别表示内置图和纯色。 */
    val lightBackgroundPath: String? = null,
    val darkBackgroundPath: String? = null,
    /** 背景媒体类型：null/"image"/"video"；保留字段以兼容旧配置并回退旧值。 */
    val lightBackgroundKind: String? = null,
    val darkBackgroundKind: String? = null,
    /** 视频背景的独立路径；与图片路径分开，切换图片/视频不丢另一份选择。 */
    val lightVideoPath: String? = null,
    val darkVideoPath: String? = null,
    /** 视频背景声音默认关闭，只有用户明确开启才输出音频；音量 0..1。 */
    val videoSoundEnabled: Boolean = false,
    val videoVolume: Float = 0.8f,
    /**
     * 用户自定义的左上角品牌图标：只替换主窗口标题栏左侧的品牌标识。
     * 为空表示使用内置图标；文件副本保存在 MoliFox/appearance/branding，标题栏、任务栏与准备窗口仍用内置图标。
     */
    val brandIconPath: String = "",
    /** 自定义图标的内容版本：同一路径重新选择图片时更新，界面据此重新读取。 */
    val brandIconRevision: Long = 0,
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
    val cornerRadiusDp: Int = 6,
    val fontChoice: String = "",
    val fontScale: Float = 1f,
    val customFonts: List<LauncherFont> = emptyList(),
    val pages: PagePreferences = PagePreferences(),
    /** 主题颜色：预设键（sakura/ocean/mint/amber/lavender/graphite）或 #RRGGBB 自定义。 */
    val accentColor: String = "sakura",
    val automaticAccent: Boolean = false,
    /** 配色来源：background（跟随背景图取色）或 manual（自定义）；与手动种子分开保存，切回自定义不丢失选色。 */
    val themeColorSource: String = "manual",
    /** 关闭后页面切换与提示不使用过渡动画。 */
    val animationsEnabled: Boolean = true,
    /** 标题栏半透明（无需玻璃效果也生效）。 */
    val titlebarTransparent: Boolean = false,
    /** 整窗透明（重启后生效）。 */
    val windowTransparent: Boolean = false,
    /** 界面语言：auto 跟随系统，或 zh-CN / zh-TW / zh-HK / en-US；旧配置保留原值。 */
    val language: String = "auto",
    /** 已同意的用户协议版本；为空或低于当前版本时启动器会再次询问。 */
    val agreementVersion: String = "",
    val gameLanguageFollowsLauncher: Boolean = true,
    /** 启动时后台检查更新；已有配置中主动关闭的选择保留。 */
    val autoCheckUpdates: Boolean = true,
    val allowBetaUpdates: Boolean = false,
    /** GitHub 公开资源统一线路：auto/direct/ghproxy/ghproxy-com/ghfast/custom。 */
    val githubSource: String = "auto",
    val githubCustomProxy: String = "",
    /** 版本列表源：official / bmclapi，独立于游戏文件下载源。 */
    val versionListSource: String = "auto",
    /** 附加内容（模组/整合包等）站点：modrinth / curseforge。 */
    val contentDownloadSource: String = "modrinth",
    /** 文件下载缓存文件夹；为空使用数据目录 cache/。 */
    val downloadCachePath: String = "",
    /** 同时下载的文件数（默认 64），新任务采用当前设置。 */
    val downloadThreads: Int = 64,
    val proxyEnabled: Boolean = false,
    /** 空值沿用旧代理开关；新配置默认跟随系统，支持 system/direct/manual。 */
    val proxyMode: String = "",
    val proxyType: String = "http",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    /** CurseForge 服务地址；默认社区镜像，可改为自建代理。请求始终追加 `/v1/`。 */
    val curseForgeBaseUrl: String = "",
    val versionIsolationPolicy: VersionIsolationPolicy? = null,
    /** 全局游戏窗口模式：launcher（与启动器一样大）/ fullscreen / custom。 */
    val gameWindowMode: String = "custom",
    /** 游戏进程优先级：仅 Windows 生效，启动游戏后尽力设置，失败只记警告。 */
    val processPriority: ProcessPriority = ProcessPriority.Normal,
)

/** 统一 Java 下载来源，未知值使用自动选择。 */
fun normalizedJavaDownloadSource(value: String): String =
    value.takeIf { it in setOf("auto", "azul", "adoptium", "oracle") } ?: "auto"

val LauncherSettings.effectiveJavaDownloadSource: String
    get() = normalizedJavaDownloadSource(javaDownloadSource)

/** 版本列表源与文件下载源共用的三种取值；旧值（mirror、未知字符串）一律按自动处理。 */
fun normalizedDownloadSource(value: String): String = if (value == "official" || value == "bmclapi") value else "auto"

/** 版本清单（manifest）用的源，独立于文件下载源。 */
val LauncherSettings.effectiveVersionListSource: String
    get() = normalizedDownloadSource(versionListSource)

/** 本体、依赖库、资源文件与 Java 用的源。 */
val LauncherSettings.effectiveDownloadSource: String
    get() = normalizedDownloadSource(downloadSource)

/**
 * CurseForge 的默认社区镜像（公开可读、不需要 API Key）。 自建代理只需在设置里替换地址。
 */
const val DEFAULT_CURSE_FORGE_BASE_URL = "https://mod.mcimirror.top/curseforge"

/** 服务地址必须是绝对 http(s) URL；查询串、片段和用户信息不属于服务根地址。 */
fun isValidCurseForgeBaseUrl(value: String): Boolean =
    isValidServiceRootUrl(value) { scheme ->
        scheme?.lowercase() == "http" || scheme?.lowercase() == "https"
    }

/**
 * 实际请求使用的服务根地址：空值或非法值回落到默认镜像，末尾斜杠会被去掉。
 * 调用方（CurseForgeBackend）在回落时写一条日志，避免静默使用非预期服务。
 */
fun normalizedCurseForgeBaseUrl(value: String): String =
    if (isValidCurseForgeBaseUrl(value)) {
        value.trim().trimEnd('/')
    } else {
        DEFAULT_CURSE_FORGE_BASE_URL
    }

/** 设置里实际生效的 CurseForge 服务地址；UI 与请求层都读这个。 */
val LauncherSettings.effectiveCurseForgeBaseUrl: String
    get() = normalizedCurseForgeBaseUrl(curseForgeBaseUrl.orEmpty())

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
 * 内置的默认外置认证服务器。LittleSkin 是公开的 Yggdrasil 兼容服务，预置一条可让用户
 * 不必自己找地址，账户页可直接选它登录，也可以删掉或添加别的服务器。
 */
val DEFAULT_AUTHLIB_SERVER =
    AuthlibServer(url = "https://littleskin.cn/api/yggdrasil", name = "LittleSkin")

data class GameInstance(
    /**
     * 运行时标识。它由扫描时按游戏目录 + 文件夹名生成（见 [InstanceCatalog.instanceId]），不是配置里的记录；
     * 重启后同一目录得到同一 id。旧配置迁移时沿用原 id 一次，之后的扫描再收敛到稳定值。
     */
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val iconStyle: InstanceIconStyle = InstanceIconStyle.Auto,
    val iconId: String = "",
    /** Pack-provided artwork is separate from a user's explicit choice. */
    val packIconId: String = "",
    val gameVersion: String = "",
    val versionId: String = "",
    val gameDirectoryName: String = "",
    val loader: LoaderType = LoaderType.Vanilla,
    val loaderVersion: String = "",
    val createdAt: String = Instant.now().toString(),
    val lastPlayed: String? = null,
    val playTimeSeconds: Long = 0,
    val installed: Boolean = false,
    val memoryMb: Int = 0,
    /** 扫描时统计的 mods 文件数；只用于自动内存估算，不写入实例设置。 */
    val modCount: Int = 0,
    val autoMemory: Boolean? = null,
    val javaPath: String = "",
    /** 用户点过「不再提示」后不再主动弹缺 Java 的窗口；手动指定 Java 路径不受影响。 */
    val suppressJavaPrompt: Boolean = false,
    val externalProfile: Boolean = false,
    val packFile: String = "",
    val gameDirectoryId: String? = null,
    val isolation: VersionIsolation = VersionIsolation.Inherit,
    val initialMemoryMb: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val windowMode: String = "inherit",
    val extraJvmArgs: String = "",
    /** 实例自己的额外游戏参数，追加在标准参数之后；空串表示不追加。 */
    val extraGameArgs: String = "",
    val notes: String = "",
    val favorite: Boolean = false,
    /** 恢复存档时用备份替换原世界（默认关闭，关闭时只解压成同级的新世界，不碰原存档）。 */
    val replaceSaveOnRestore: Boolean = false,
    val managedGameDirectory: Boolean = false,
    val packOptionalFiles: List<String>? = null,
    val advancedLaunch: AdvancedLaunchSettings? = null,
    /** 扫描时发现版本配置缺失/损坏或 inheritsFrom 断链：仍显示，但不可启动。 */
    val broken: Boolean = false,
    val brokenReason: String = "",
    val gameVersionType: String = "",
)

/**
 * 按实例目录名保存的持久设置。实例列表本身不再写入配置，`versions/` 是唯一事实来源；
 * 这里只保留必须跨重启保留、又无法从版本 JSON 推导的字段；都按「每个实例一份」保存，
 * 而不是塞进全局设置里。
 *
 * 前五项是本次架构要求的最小集合：隔离模式、指定 Java、图标、收藏、最近游玩；
 * 其余字段是现有版本设置页已经在用的启动参数，继续放在同一张表里，避免这次重构丢失用户设置。
 */
data class InstanceSettings(
    val isolation: VersionIsolation = VersionIsolation.Inherit,
    val javaPath: String = "",
    val iconId: String = "",
    val favorite: Boolean = false,
    val lastPlayed: String? = null,
    // 用户给实例起的显示名；为空时直接显示目录名。
    val displayName: String = "",
    val packIconId: String = "",
    val playTimeSeconds: Long = 0,
    val memoryMb: Int = 0,
    val autoMemory: Boolean? = null,
    val initialMemoryMb: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val windowMode: String = "inherit",
    val extraJvmArgs: String = "",
    val extraGameArgs: String = "",
    val notes: String = "",
    val suppressJavaPrompt: Boolean = false,
    val advancedLaunch: AdvancedLaunchSettings? = null,
    // 整合包来源：修复时需要重新导入同一文件；不是版本 JSON 能推导的信息。
    val packFile: String = "",
    val packOptionalFiles: List<String>? = null,
    val managedGameDirectory: Boolean = false,
    val replaceSaveOnRestore: Boolean = false,
) {
    companion object {
        fun of(instance: GameInstance): InstanceSettings =
            InstanceSettings(
                isolation = instance.isolation,
                javaPath = instance.javaPath,
                iconId = instance.iconId,
                favorite = instance.favorite,
                lastPlayed = instance.lastPlayed,
                displayName = instance.name,
                packIconId = instance.packIconId,
                playTimeSeconds = instance.playTimeSeconds,
                memoryMb = instance.memoryMb,
                autoMemory = instance.autoMemory,
                initialMemoryMb = instance.initialMemoryMb,
                width = instance.width,
                height = instance.height,
                windowMode = instance.windowMode,
                extraJvmArgs = instance.extraJvmArgs,
                extraGameArgs = instance.extraGameArgs,
                notes = instance.notes,
                suppressJavaPrompt = instance.suppressJavaPrompt,
                advancedLaunch = instance.advancedLaunch,
                packFile = instance.packFile,
                packOptionalFiles = instance.packOptionalFiles,
                managedGameDirectory = instance.managedGameDirectory,
                replaceSaveOnRestore = instance.replaceSaveOnRestore,
            )
    }

    /** 把设置覆盖到扫描出的实例上；扫描结果负责名称、版本、加载器、安装与损坏状态。 */
    fun applyTo(instance: GameInstance): GameInstance =
        instance.copy(
            name = displayName.ifBlank { instance.name },
            isolation = isolation,
            javaPath = javaPath,
            iconId = iconId,
            favorite = favorite,
            lastPlayed = lastPlayed,
            packIconId = packIconId,
            playTimeSeconds = playTimeSeconds,
            memoryMb = memoryMb,
            autoMemory = autoMemory,
            initialMemoryMb = initialMemoryMb,
            width = width,
            height = height,
            windowMode = windowMode,
            extraJvmArgs = extraJvmArgs,
            extraGameArgs = extraGameArgs,
            notes = notes,
            suppressJavaPrompt = suppressJavaPrompt,
            advancedLaunch = advancedLaunch,
            packFile = packFile,
            packOptionalFiles = packOptionalFiles,
            managedGameDirectory = managedGameDirectory,
            replaceSaveOnRestore = replaceSaveOnRestore,
        )
}

/** 实例在 `versions/` 下的目录名；新模型里这就是实例身份。 */
fun GameInstance.instanceFolderName(): String = gameDirectoryName.ifBlank { versionId.ifBlank { name } }

/** 设置表的键：实例目录名（实例身份就是文件夹名）。 */
fun GameInstance.instanceSettingsKey(): String = instanceFolderName()

/**
 * 正在构造、尚未写出 `<name>/<name>.json` 的安装目标。只存在内存里，不写入配置；
 * 扫描时跳过它的空目录，避免安装期间闪出一个「无法识别」实例。
 */
data class PendingInstall(
    val name: String,
    val gameVersion: String,
    val loaderId: String = "",
)

data class LauncherData(
    val schemaVersion: Int = 2,
    val settings: LauncherSettings = LauncherSettings(),
    val accounts: List<Account> = emptyList(),
    // 运行时投影，不写入配置：每次扫描 versions/*/ 重建。它不再是实例记录列表。
    @Transient val instances: List<GameInstance> = emptyList(),
    val activeAccountId: String? = null,
    val selectedInstanceId: String? = null,
    /** 用户添加过的外置认证服务器列表（仅 URL 与显示名，令牌在凭据库中）。 */
    val authlibServers: List<AuthlibServer> = listOf(DEFAULT_AUTHLIB_SERVER),
    val gameDirectories: List<GameDirectory> = emptyList(),
    val activeGameDirectoryId: String? = null,
    /** 按实例目录名索引的持久设置；不存在的目录留下的条目是惰性的，不参与列表。 */
    val instanceSettings: Map<String, InstanceSettings> = emptyMap(),
)

/** 新建任务的初始进度文案：在实例创建时按当前语言取一次，默认值里不直接调 [I18n]。 */
private fun queuedTaskDetail(): String = I18n.t("miscA2.models.downloadJobQueued")

data class DownloadJob(
    val id: String,
    val title: String,
    val kind: LauncherTaskKind = LauncherTaskKind.Generic,
    val status: JobStatus = JobStatus.Queued,
    val progress: Float = 0f,
    val detail: String = queuedTaskDetail(),
    val error: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val bytesPerSecond: Long = 0,
    /** 正在传数据的下载连接数：随传输进度一起发布，任务中心展示实际并发。 */
    val activeConnections: Int = 0,
    val resources: Set<String> = setOf(GLOBAL_TASK_RESOURCE),
    val downloadTotalPending: Boolean = false,
)

/**
 * 日志级别：档位与 log4j / java.util.logging 一致，界面筛选与 `logs/launcher.log` 使用同一套名字。
 * 每一级都必须有真实产出点（见 LauncherStore.log 的调用方），否则界面不提供对应筛选项：
 * TRACE = 命令输出逐行；DEBUG = 后台任务开始/完成；WARN = 尽力而为的步骤失败；
 * ERROR = 操作失败但启动器可继续；FATAL = 启动中止。
 */
enum class LogLevel {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
    Fatal,
    ;

    /** 显示名在读取时翻译；写文件的档位名见 [label]。 */
    val title: String
        get() =
            when (this) {
                Trace -> I18n.t("core.logLevel.trace")
                Debug -> I18n.t("core.logLevel.debug")
                Info -> I18n.t("core.logLevel.info")
                Warn -> I18n.t("core.logLevel.warn")
                Error -> I18n.t("core.logLevel.error")
                Fatal -> I18n.t("core.logLevel.fatal")
            }

    /** 写进日志文件与导出文件的档位名。 */
    val label: String
        get() = name.uppercase()
}

data class LogLine(
    /** 界面临时列表的稳定 key；不参与日志文件与导出。 */
    val id: Long = 0,
    val time: String = Instant.now().toString(),
    val message: String,
    val isError: Boolean = false,
    val isGame: Boolean = false,
    val level: LogLevel = if (isError) LogLevel.Error else LogLevel.Info,
)

data class LauncherState(
    val data: LauncherData,
    val jobs: List<DownloadJob> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val runningInstanceId: String? = null,
    val runningGames: List<GameRunEvent> = emptyList(),
    val toast: String? = null,
    val launchIssue: LaunchIssue? = null,
    val gameRun: GameRunEvent? = null,
    val recentUsageRevision: Long = 0,
    val update: LauncherUpdateState = LauncherUpdateState(),
    /** 当前实例缺少兼容 Java 时的安装/选择入口；为空表示无需提示。 */
    val javaPrompt: JavaPrompt? = null,
    /** 本次提示来自启动请求时，安装/选择完成后要接着启动的实例。 */
    val pendingJavaLaunch: PendingJavaLaunch? = null,
)

data class ReleaseVersion(
    val id: String,
    val type: String,
    val url: String,
    val releaseTime: String,
    val sha1: String? = null,
)

data class LoaderVersion(
    val version: String,
    val stable: Boolean = true,
)

data class InstallRequest(
    val name: String,
    val gameVersion: String,
    val loader: LoaderType = LoaderType.Vanilla,
    val loaderVersion: String = "",
)

/** Java 来源：启动器自己安装（bundled）/ 系统（system）/ 用户显式指定（custom）。 */
enum class JavaRuntimeSource {
    Custom,
    Bundled,
    System,
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() =
            when (this) {
                Custom -> I18n.t("core.javaRuntimeSource.custom")
                Bundled -> I18n.t("core.javaRuntimeSource.bundled")
                System -> I18n.t("core.javaRuntimeSource.system")
            }
}

data class JavaRuntime(
    val path: String,
    val majorVersion: Int,
    val vendor: String,
    val architecture: String = "",
    /** 完整版本号，例如 21.0.5 或 1.8.0_512。 */
    val version: String = "",
    /** 32 / 64；0 表示未知。 */
    val bits: Int = 0,
    val source: JavaRuntimeSource = JavaRuntimeSource.System,
    /** jre / jdk；仅启动器安装的条目有值。 */
    val imageType: String = "",
    val installedAt: String = "",
    val sizeBytes: Long = 0,
    /** 不可用原因：「路径不存在」/「无效」；空串表示可用。无效条目保留在列表里，不自动清除。 */
    val invalidReason: String = "",
) {
    val managed: Boolean
        get() = source == JavaRuntimeSource.Bundled

    val usable: Boolean
        get() = invalidReason.isBlank()

    /** 用户可读名称，例如「Zulu OpenJDK 21（JRE）」。 */
    val displayName: String
        get() {
            val type = imageType.uppercase()
            return if (vendor.isBlank()) {
                "Java $majorVersion"
            } else {
                "$vendor Java $majorVersion" + if (type.isBlank()) "" else "（$type）"
            }
        }
}

/** 启动检查发现当前实例缺少可用的 Java；[requiredMajor] 已按 gameJavaMajor 的版本表换算。 */
data class JavaPrompt(
    val instanceId: String,
    val instanceName: String,
    val gameVersion: String,
    val requiredMajor: Int,
    val candidates: List<JavaRuntime> = emptyList(),
)

/** 一次启动请求的结果：要么已进入任务队列，要么缺 Java 并已登记提示。 */
sealed interface LaunchAttempt {
    data class Started(
        val jobId: String,
    ) : LaunchAttempt

    data object NeedsJava : LaunchAttempt
}

/** 缺 Java 提示若由一次启动请求触发，安装/选择完成后继续这次启动。 */
data class PendingJavaLaunch(
    val instanceId: String,
    val target: GameLaunchTarget? = null,
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
    /** 项目分类（Modrinth 的 display_categories slug 或 CurseForge 类别名）；来源未提供时保持空列表，不伪造分类。 */
    val categories: List<String> = emptyList(),
)

data class ContentSearch(
    val hits: List<ContentHit>,
    val total: Int,
)

data class DeviceCode(
    val userCode: String,
    val verificationUri: String,
    val expiresAt: Long,
)

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
    /** 本次官方认证已读取的披风；仅随运行凭据传递，不写入配置。null 表示尚未读取。 */
    val capes: List<MinecraftCape>? = null,
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

    /** 自动更新替换脚本已启动后退出启动器；测试环境可覆盖。 */
    fun exitLauncher() {
        kotlin.system.exitProcess(0)
    }
}
