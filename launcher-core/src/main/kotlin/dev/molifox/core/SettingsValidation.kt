package dev.molifox.core

import java.nio.file.Path
import java.util.UUID

// 校验边界：各设置项允许的取值，数值与界面选项保持一致。
private const val MAX_DOWNLOAD_ATTEMPTS = 8
private const val MIN_TIMEOUT_SECONDS = 10
private const val MAX_TIMEOUT_SECONDS = 180
private const val DOWNLOAD_SEGMENT_OPTION_FOUR = 4
private const val DOWNLOAD_SEGMENT_OPTION_EIGHT = 8
private const val MIRROR_CONCURRENCY_OPTION_FOUR = 4
private const val MIRROR_CONCURRENCY_OPTION_EIGHT = 8
private const val MIN_MEMORY_MB = 512
private const val MIN_GAME_WINDOW_WIDTH = 640
private const val MAX_GAME_WINDOW_WIDTH = 7680
private const val MIN_GAME_WINDOW_HEIGHT = 480
private const val MAX_GAME_WINDOW_HEIGHT = 4320
private const val MIN_INITIAL_MEMORY_MB = 128
private const val MAX_CORNER_RADIUS_DP = 24
private const val MIN_UI_SCALE = 0.85f
private const val MAX_UI_SCALE = 1.2f
private const val MIN_SURFACE_OPACITY = 0.6f
private const val MAX_SURFACE_OPACITY = 1f
private const val MAX_BACKGROUND_BLUR = 40f
private const val MIN_FONT_SCALE = 0.85f
private const val MAX_FONT_SCALE = 1.25f
private const val MAX_FONT_CHOICE_LENGTH = 2048
private const val MAX_CUSTOM_FONT_COUNT = 12
private const val MIN_LOG_FONT_SIZE = 10
private const val MAX_LOG_FONT_SIZE = 18
private const val MAX_CONCURRENT_DOWNLOADS = 32
private const val MAX_PROXY_PORT = 65535

/**
 * 设置校验：从 [LauncherController] 抽出的纯校验逻辑。
 * 只读入当前设置、实例是否为空、平台信息与「是否空闲」回调，不直接碰存储和界面。
 *
 * 旧的 mirror（国内镜像站）与未知下载源取值在 [normalizedSettings] 里迁移成当前支持的取值。
 */
internal fun normalizedSettings(input: LauncherSettings): LauncherSettings =
    input.copy(
        javaDownloadSource = input.effectiveJavaDownloadSource,
        downloadSource = input.effectiveDownloadSource,
        versionListSource = input.effectiveVersionListSource,
        mirrorConcurrency = normalizedMirrorConcurrency(input.mirrorConcurrency),
        // 旧配置里可能残留越界值：读入时收敛到可执行范围，避免整份设置校验失败。
        downloadThreads = input.downloadThreads.coerceIn(1, MAX_DOWNLOAD_THREADS),
    )

internal fun validateSettings(
    settings: LauncherSettings,
    current: LauncherSettings,
    instancesEmpty: Boolean,
    osName: String,
    totalMemoryMb: Int,
    isIdle: () -> Unit,
) {
    validateEngineSettings(settings, current, osName, isIdle)
    validateGameRootSettings(settings, current, instancesEmpty, isIdle)
    validateMemoryAndWindowSettings(settings, totalMemoryMb)
    validateBackgroundSettings(settings)
    validateScaleSettings(settings)
    validateDownloadSettings(settings)
    validateInterfaceSettings(settings)
    validateProxySettings(settings)
    validateExtraJvmArguments(settings.extraJvmArgs)
}

private fun validateEngineSettings(
    settings: LauncherSettings,
    current: LauncherSettings,
    osName: String,
    isIdle: () -> Unit,
) {
    require(
        settings.downloadAttempts in 1..MAX_DOWNLOAD_ATTEMPTS &&
            settings.networkTimeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS,
    ) {
        "重试次数或网络超时无效"
    }
    require(
        settings.downloadSegments in
            setOf(1, 2, DOWNLOAD_SEGMENT_OPTION_FOUR, DOWNLOAD_SEGMENT_OPTION_EIGHT),
    ) { "单文件分段数无效" }
    require(
        settings.mirrorConcurrency in
            setOf(2, MIRROR_CONCURRENCY_OPTION_FOUR, MIRROR_CONCURRENCY_OPTION_EIGHT),
    ) { "镜像并发无效" }
    validateAdvancedLaunchSettings(settings.advancedLaunch, osName)
    if (settings.isolateVersions != current.isolateVersions) isIdle()
}

private fun validateGameRootSettings(
    settings: LauncherSettings,
    current: LauncherSettings,
    instancesEmpty: Boolean,
    isIdle: () -> Unit,
) {
    require(Path.of(settings.gameRoot).isAbsolute && Path.of(settings.gameRoot).parent != null) {
        "请选择专用的游戏文件夹"
    }
    if (settings.gameRoot != current.gameRoot) {
        isIdle()
        require(instancesEmpty) { "已有实例时不能直接切换目录，请先导出并删除实例" }
    }
}

private fun validateMemoryAndWindowSettings(
    settings: LauncherSettings,
    totalMemoryMb: Int,
) {
    require(
        settings.memoryMb in MIN_MEMORY_MB..maxOf(MIN_MEMORY_MB, totalMemoryMb - MIN_MEMORY_MB),
    ) {
        "内存需在 512 MB 到物理内存减 512 MB 之间"
    }
    require(
        settings.width in MIN_GAME_WINDOW_WIDTH..MAX_GAME_WINDOW_WIDTH &&
            settings.height in MIN_GAME_WINDOW_HEIGHT..MAX_GAME_WINDOW_HEIGHT,
    ) { "游戏窗口尺寸无效" }
    require(settings.initialMemoryMb in MIN_INITIAL_MEMORY_MB..settings.memoryMb) {
        "初始内存需在 128 MB 到最大内存之间"
    }
}

private fun validateBackgroundSettings(settings: LauncherSettings) {
    require(settings.themeMode in listOf("", "light", "dark", "system")) { "主题模式无效" }
    require(settings.backgroundFit in listOf("crop", "fit", "stretch")) { "背景适配模式无效" }
    require(settings.cornerRadiusDp in 0..MAX_CORNER_RADIUS_DP) { "界面圆角需在 0–24 dp 之间" }
    require(
        settings.backgroundAlignment in
            listOf(
                "topLeft",
                "topCenter",
                "topRight",
                "centerLeft",
                "center",
                "centerRight",
                "bottomLeft",
                "bottomCenter",
                "bottomRight",
            ),
    ) {
        "背景对齐位置无效"
    }
}

private fun validateScaleSettings(settings: LauncherSettings) {
    require(settings.uiScale.isFinite() && settings.uiScale in MIN_UI_SCALE..MAX_UI_SCALE) {
        "界面缩放需在 85%–120% 之间"
    }
    require(settings.backgroundOpacity.isFinite() && settings.backgroundOpacity in 0f..1f) {
        "背景浓度需在 0–100% 之间"
    }
    require(
        settings.surfaceOpacity.isFinite() &&
            settings.surfaceOpacity in MIN_SURFACE_OPACITY..MAX_SURFACE_OPACITY,
    ) {
        "面板不透明度需在 60%–100% 之间"
    }
    require(
        settings.backgroundBlur.isFinite() &&
            settings.backgroundBlur in 0f..MAX_BACKGROUND_BLUR,
    ) {
        "磨砂强度需在 0–40 之间"
    }
    require(
        settings.fontScale.isFinite() && settings.fontScale in MIN_FONT_SCALE..MAX_FONT_SCALE,
    ) {
        "字号需在 85%–125% 之间"
    }
    require(
        settings.fontChoice.length <= MAX_FONT_CHOICE_LENGTH &&
            settings.customFonts.size <= MAX_CUSTOM_FONT_COUNT,
    ) {
        "字体设置无效"
    }
    require(
        settings.pages.versions.view in listOf("list", "grid") &&
            settings.pages.versions.sort in listOf("recent", "name", "created") &&
            settings.pages.logs.defaultTab in listOf("tasks", "logs") &&
            settings.pages.logs.fontSize in MIN_LOG_FONT_SIZE..MAX_LOG_FONT_SIZE,
    ) {
        "页面设置无效"
    }
}

private fun validateDownloadSettings(settings: LauncherSettings) {
    require(
        settings.maxConcurrent in 1..MAX_CONCURRENT_DOWNLOADS &&
            settings.downloadThreads in 1..MAX_DOWNLOAD_THREADS &&
            settings.downloadSource in listOf("auto", "official", "bmclapi") &&
            settings.versionListSource in listOf("auto", "official", "bmclapi") &&
            settings.contentDownloadSource in listOf("modrinth", "curseforge"),
    ) {
        "下载设置无效"
    }
    require(settings.javaDownloadSource in listOf("auto", "azul", "adoptium")) { "Java 下载源无效" }
}

private fun validateInterfaceSettings(settings: LauncherSettings) {
    require(
        settings.accentColor == "sakura" ||
            Regex("^#[0-9A-Fa-f]{6}$").matches(settings.accentColor) ||
            settings.accentColor in
            listOf(
                "ocean",
                "mint",
                "amber",
                "lavender",
                "graphite",
            ),
    ) {
        "主题颜色无效"
    }
    require(settings.language == "zh-CN") { "当前版本仅提供简体中文界面" }
    require(settings.gameWindowMode in listOf("launcher", "fullscreen", "custom")) {
        "游戏窗口模式无效"
    }
}

private fun validateProxySettings(settings: LauncherSettings) {
    require(settings.proxyType in listOf("http", "socks")) { "代理类型无效" }
    require(settings.proxyMode in listOf("", "system", "direct", "manual")) { "代理模式无效" }
    if (settings.effectiveProxyMode() == "manual") {
        require(
            settings.proxyHost.isNotBlank() &&
                !settings.proxyHost.contains(':') &&
                settings.proxyPort in 1..MAX_PROXY_PORT,
        ) {
            "代理地址或端口无效"
        }
    }
    if (settings.downloadCachePath.isNotBlank()) {
        require(Path.of(settings.downloadCachePath).isAbsolute) { "下载缓存文件夹必须是绝对路径" }
    }
    require(
        settings.curseForgeBaseUrl.isBlank() ||
            isValidCurseForgeBaseUrl(settings.curseForgeBaseUrl),
    ) {
        "CurseForge 服务地址需为 http:// 或 https:// 开头的完整地址"
    }
    require(
        settings.microsoftClientId.isBlank() ||
            runCatching { UUID.fromString(settings.microsoftClientId) }.isSuccess,
    ) {
        "Microsoft 客户端 ID 格式无效"
    }
}
