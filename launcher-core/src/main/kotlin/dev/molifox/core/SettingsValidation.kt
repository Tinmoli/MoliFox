package dev.molifox.core

import dev.molifox.core.i18n.AppLanguage
import dev.molifox.core.i18n.I18n
import java.nio.file.Path
import java.util.UUID

// 校验边界：各设置项允许的取值，数值与界面选项保持一致。
private const val MIN_TIMEOUT_SECONDS = 10
private const val MAX_TIMEOUT_SECONDS = 180
private const val MIN_DOWNLOAD_SEGMENTS = 1
internal const val MAX_DOWNLOAD_SEGMENTS = 16
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
private const val MAX_PROXY_PORT = 65535

/** 背景媒体类型：静态/动态图片或本地视频；旧 gradient 值读取时回退为 image。 */
val BACKGROUND_KINDS = setOf("image", "video")

/**
 * 设置校验：从 [LauncherController] 抽出的纯校验逻辑。
 * 只读入当前设置、实例是否为空、平台信息与「是否空闲」回调，不直接碰存储和界面。
 *
 * 旧的 mirror（国内镜像站）与未知下载源取值在 [normalizedSettings] 里迁移成当前支持的取值。
 */
internal fun normalizedSettings(input: LauncherSettings): LauncherSettings =
    input.copy(
        accentColor = normalizedAccentColor(input.accentColor),
        javaDownloadSource = input.effectiveJavaDownloadSource,
        downloadSource = input.effectiveDownloadSource,
        versionListSource = input.effectiveVersionListSource,
        githubSource = input.githubSource.takeIf { key -> GitHubSource.entries.any { it.key == key } } ?: "auto",
        // 旧配置里可能残留越界值：读入时收敛到可执行范围，避免整份设置校验失败。
        downloadThreads = input.downloadThreads.coerceIn(1, MAX_DOWNLOAD_THREADS),
        downloadSegments =
            input.downloadSegments.coerceIn(
                MIN_DOWNLOAD_SEGMENTS,
                MAX_DOWNLOAD_SEGMENTS,
            ),
        videoVolume = input.videoVolume.coerceIn(0f, 1f),
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
    require(settings.networkTimeoutSeconds in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
        I18n.t("core.settingsValidation.1")
    }
    require(settings.downloadSegments in MIN_DOWNLOAD_SEGMENTS..MAX_DOWNLOAD_SEGMENTS) {
        I18n.t("core.settingsValidation.2")
    }
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
        I18n.t("core.settingsValidation.4")
    }
    if (settings.gameRoot != current.gameRoot) {
        isIdle()
        require(instancesEmpty) { I18n.t("core.settingsValidation.5") }
    }
}

private fun validateMemoryAndWindowSettings(
    settings: LauncherSettings,
    totalMemoryMb: Int,
) {
    require(
        settings.memoryMb in MIN_MEMORY_MB..maxOf(MIN_MEMORY_MB, totalMemoryMb - MIN_MEMORY_MB),
    ) {
        I18n.t("core.settingsValidation.6")
    }
    require(
        settings.width in MIN_GAME_WINDOW_WIDTH..MAX_GAME_WINDOW_WIDTH &&
            settings.height in MIN_GAME_WINDOW_HEIGHT..MAX_GAME_WINDOW_HEIGHT,
    ) { I18n.t("core.settingsValidation.7") }
    require(settings.initialMemoryMb in MIN_INITIAL_MEMORY_MB..settings.memoryMb) {
        I18n.t("core.settingsValidation.8")
    }
}

private fun validateBackgroundSettings(settings: LauncherSettings) {
    require(settings.themeMode in listOf("", "light", "dark", "system")) { I18n.t("core.settingsValidation.9") }
    require(settings.backgroundFit in listOf("crop", "fit", "stretch")) { I18n.t("core.settingsValidation.10") }
    require(settings.cornerRadiusDp in 0..MAX_CORNER_RADIUS_DP) { I18n.t("core.settingsValidation.11") }
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
        I18n.t("core.settingsValidation.12")
    }
    require(settings.lightBackgroundKind == null || settings.lightBackgroundKind in BACKGROUND_KINDS) {
        I18n.t("core.settingsValidation.32")
    }
    require(settings.darkBackgroundKind == null || settings.darkBackgroundKind in BACKGROUND_KINDS) {
        I18n.t("core.settingsValidation.32")
    }
}

private fun validateScaleSettings(settings: LauncherSettings) {
    require(settings.uiScale.isFinite() && settings.uiScale in MIN_UI_SCALE..MAX_UI_SCALE) {
        I18n.t("core.settingsValidation.13")
    }
    require(settings.backgroundOpacity.isFinite() && settings.backgroundOpacity in 0f..1f) {
        I18n.t("core.settingsValidation.14")
    }
    require(
        settings.surfaceOpacity.isFinite() &&
            settings.surfaceOpacity in MIN_SURFACE_OPACITY..MAX_SURFACE_OPACITY,
    ) {
        I18n.t("core.settingsValidation.15")
    }
    require(
        settings.backgroundBlur.isFinite() &&
            settings.backgroundBlur in 0f..MAX_BACKGROUND_BLUR,
    ) {
        I18n.t("core.settingsValidation.16")
    }
    require(
        settings.fontScale.isFinite() && settings.fontScale in MIN_FONT_SCALE..MAX_FONT_SCALE,
    ) {
        I18n.t("core.settingsValidation.17")
    }
    require(
        settings.fontChoice.length <= MAX_FONT_CHOICE_LENGTH &&
            settings.customFonts.size <= MAX_CUSTOM_FONT_COUNT,
    ) {
        I18n.t("core.settingsValidation.18")
    }
    require(
        settings.pages.versions.view in listOf("list", "grid") &&
            settings.pages.versions.sort in listOf("recent", "name", "created") &&
            settings.pages.logs.defaultTab in listOf("tasks", "logs") &&
            settings.pages.logs.fontSize in MIN_LOG_FONT_SIZE..MAX_LOG_FONT_SIZE,
    ) {
        I18n.t("core.settingsValidation.19")
    }
}

private fun validateDownloadSettings(settings: LauncherSettings) {
    require(GitHubSource.entries.any { it.key == settings.githubSource }) { I18n.t("settings.github.invalidSource") }
    require(settings.githubSource != "custom" || isValidGitHubProxy(settings.githubCustomProxy)) {
        I18n.t("settings.github.invalidAddress")
    }
    require(
        settings.downloadThreads in 1..MAX_DOWNLOAD_THREADS &&
            settings.downloadSource in listOf("auto", "official", "bmclapi") &&
            settings.versionListSource in listOf("auto", "official", "bmclapi") &&
            settings.contentDownloadSource in listOf("modrinth", "curseforge"),
    ) {
        I18n.t("core.settingsValidation.20")
    }
    require(settings.javaDownloadSource in listOf("auto", "azul", "adoptium")) { I18n.t("core.settingsValidation.21") }
}

private fun validateInterfaceSettings(settings: LauncherSettings) {
    require(settings.videoVolume in 0f..1f) { I18n.t("core.settingsValidation.33") }
    require(settings.themeColorSource in setOf("background", "manual")) {
        I18n.t("settings.appearance.accent.invalidSource")
    }
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
        I18n.t("core.settingsValidation.22")
    }
    require(AppLanguage.options.any { it.settingValue.equals(settings.language.trim(), ignoreCase = true) }) {
        I18n.t("core.settingsValidation.23")
    }
    require(settings.gameWindowMode in listOf("launcher", "fullscreen", "custom")) {
        I18n.t("core.settingsValidation.24")
    }
}

private fun validateProxySettings(settings: LauncherSettings) {
    require(settings.proxyType in listOf("http", "socks")) { I18n.t("core.settingsValidation.25") }
    require(settings.proxyMode in listOf("", "system", "direct", "manual")) { I18n.t("core.settingsValidation.26") }
    if (settings.effectiveProxyMode() == "manual") {
        require(
            settings.proxyHost.isNotBlank() &&
                !settings.proxyHost.contains(':') &&
                settings.proxyPort in 1..MAX_PROXY_PORT,
        ) {
            I18n.t("core.settingsValidation.27")
        }
    }
    if (settings.downloadCachePath.isNotBlank()) {
        require(Path.of(settings.downloadCachePath).isAbsolute) { I18n.t("core.settingsValidation.28") }
    }
    require(
        settings.curseForgeBaseUrl.isBlank() ||
            isValidCurseForgeBaseUrl(settings.curseForgeBaseUrl),
    ) {
        I18n.t("core.settingsValidation.29")
    }
    require(
        settings.microsoftClientId.isBlank() ||
            runCatching { UUID.fromString(settings.microsoftClientId) }.isSuccess,
    ) {
        I18n.t("core.settingsValidation.30")
    }
}
