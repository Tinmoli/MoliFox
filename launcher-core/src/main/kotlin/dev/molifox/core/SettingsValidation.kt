package dev.molifox.core

import java.nio.file.Path
import java.util.UUID

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
    require(settings.downloadAttempts in 1..8 && settings.networkTimeoutSeconds in 10..180) {
        "重试次数或网络超时无效"
    }
    require(settings.downloadSegments in setOf(1, 2, 4, 8)) { "单文件分段数无效" }
    require(settings.mirrorConcurrency in setOf(2, 4, 8)) { "镜像并发无效" }
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
    require(settings.memoryMb in 512..maxOf(512, totalMemoryMb - 512)) {
        "内存需在 512 MB 到物理内存减 512 MB 之间"
    }
    require(settings.width in 640..7680 && settings.height in 480..4320) { "游戏窗口尺寸无效" }
    require(settings.initialMemoryMb in 128..settings.memoryMb) { "初始内存需在 128 MB 到最大内存之间" }
}

private fun validateBackgroundSettings(settings: LauncherSettings) {
    require(settings.themeMode in listOf("", "light", "dark", "system")) { "主题模式无效" }
    require(settings.backgroundFit in listOf("crop", "fit", "stretch")) { "背景适配模式无效" }
    require(settings.cornerRadiusDp in 0..24) { "界面圆角需在 0–24 dp 之间" }
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
    require(settings.uiScale.isFinite() && settings.uiScale in .85f..1.2f) {
        "界面缩放需在 85%–120% 之间"
    }
    require(settings.backgroundOpacity.isFinite() && settings.backgroundOpacity in 0f..1f) {
        "背景浓度需在 0–100% 之间"
    }
    require(settings.surfaceOpacity.isFinite() && settings.surfaceOpacity in .6f..1f) {
        "面板不透明度需在 60%–100% 之间"
    }
    require(settings.backgroundBlur.isFinite() && settings.backgroundBlur in 0f..40f) {
        "磨砂强度需在 0–40 之间"
    }
    require(settings.fontScale.isFinite() && settings.fontScale in .85f..1.25f) {
        "字号需在 85%–125% 之间"
    }
    require(settings.fontChoice.length <= 2048 && settings.customFonts.size <= 12) { "字体设置无效" }
    require(
        settings.pages.versions.view in listOf("list", "grid") &&
            settings.pages.versions.sort in listOf("recent", "name", "created") &&
            settings.pages.logs.defaultTab in listOf("tasks", "logs") &&
            settings.pages.logs.fontSize in 10..18,
    ) {
        "页面设置无效"
    }
}

private fun validateDownloadSettings(settings: LauncherSettings) {
    require(
        settings.maxConcurrent in 1..32 &&
            settings.downloadSource in listOf("auto", "official", "bmclapi") &&
            settings.versionListSource in listOf("auto", "official", "bmclapi") &&
            settings.contentDownloadSource in listOf("modrinth", "curseforge"),
    ) {
        "下载设置无效"
    }
    require(settings.javaDownloadSource in listOf("auto", "official")) { "Java 下载源无效" }
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
                settings.proxyPort in 1..65535,
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
