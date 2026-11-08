// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责设置保存与账户皮肤。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

/** 设置的校验与落盘、账户皮肤。 */
internal interface LauncherSettingsApi {
    fun saveSettings(input: LauncherSettings)

    /** 切换内容（模组/整合包等）下载源；CurseForge 走设置里的公共镜像，无需 API Key。 */
    fun setContentDownloadSource(source: String)

    /**
     * 返回账户皮肤纹理的本地缓存路径（MoliFox/cache/skins/<uuid>.png）。
     * 离线账户或未配置皮肤时为 null；在线账户首次调用会从 Mojang 官方 CDN 下载。
     */
    suspend fun accountSkin(
        account: Account,
        refresh: Boolean = false,
    ): Path?

    /** 切换实例设置「恢复时替换原世界」（默认关闭），立即写入配置。 */
    fun setReplaceSaveOnRestore(
        instanceId: String,
        enabled: Boolean,
    )
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherSettingsActions(
    core: LauncherControllerCore,
) : LauncherDirectoryActions(core),
    LauncherSettingsApi {
    override fun saveSettings(input: LauncherSettings) {
        val settings = normalizedSettings(input)
        validateSettings(
            settings = settings,
            current = store.data.settings,
            instancesEmpty = store.data.instances.isEmpty(),
            osName = platform.osName,
            totalMemoryMb = platform.totalMemoryMb,
            isIdle = ::requireIdle,
        )
        applySettings(settings)
    }

    private fun applySettings(settings: LauncherSettings) {
        applyDownloadSettings(settings)
        val javaPathChanged = settings.javaPath != store.data.settings.javaPath
        store.mutate {
            it.copy(
                settings =
                    settings.copy(
                        proxyEnabled =
                            settings.effectiveProxyMode() == "manual",
                    ),
            )
        }
        if (javaPathChanged) applyLaunchSettings()
        store.notify(I18n.t("core.settings.saved"), LauncherNoticeKind.SettingsSaved)
    }

    private fun applyDownloadSettings(settings: LauncherSettings) {
        if (settings.downloadCachePath != store.data.settings.downloadCachePath) {
            store.directories.cache(settings.downloadCachePath).initialize()
        }
        // 换源后重新评估健康度；测速顺序只属于正在执行的单次任务。
        if (
            settings.downloadSource != store.data.settings.downloadSource ||
            settings.versionListSource != store.data.settings.versionListSource
        ) {
            network.resetSourceHealth()
        }
    }

    private fun applyLaunchSettings() {
        java.invalidate()
        refreshJavaPrompt()
    }

    /** 切换内容（模组/整合包等）下载源；CurseForge 走设置里的公共镜像，无需 API Key。 */
    override fun setContentDownloadSource(source: String) {
        require(source in listOf("modrinth", "curseforge")) {
            I18n.t("miscA1.settingsActions.unknownContentSource")
        }
        store.mutate { it.copy(settings = it.settings.copy(contentDownloadSource = source)) }
        store.notify(
            if (source == "curseforge") {
                I18n.t("miscA1.settingsActions.contentSourceCurseForge")
            } else {
                I18n.t("miscA1.settingsActions.contentSourceModrinth")
            },
        )
    }

    override suspend fun accountSkin(
        account: Account,
        refresh: Boolean,
    ): Path? = skins.texture(account, refresh)

    /** 切换实例设置「恢复时替换原世界」（默认关闭），立即写入配置。 */
    override fun setReplaceSaveOnRestore(
        instanceId: String,
        enabled: Boolean,
    ) {
        val instance = store.instance(instanceId)
        if (instance.replaceSaveOnRestore == enabled) return
        saveInstanceConfiguration(instance.copy(replaceSaveOnRestore = enabled))
    }
}
