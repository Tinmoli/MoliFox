// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中下载服务、并发与超时选项，保持配置入口一致。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.DEFAULT_DOWNLOAD_SEGMENTS
import dev.molifox.core.LauncherSettings
import dev.molifox.core.MAX_DOWNLOAD_SEGMENTS
import dev.molifox.core.MAX_DOWNLOAD_THREADS
import dev.molifox.core.effectiveDownloadSource
import dev.molifox.core.effectiveProxyMode
import dev.molifox.core.effectiveVersionListSource
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** 下载：下载源、并发数、缓存文件夹与代理。 */
@Composable
internal fun downloadSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): List<SettingCard> {
    val cards = mutableListOf<SettingCard>()
    cards.add(githubSourceSettingCard(actions, writer, settings))
    cards.add(
        SettingCard(
            I18n.t("settings.download.section.title"),
            I18n.t("settings.download.section.detail"),
            listOf(
                settingField(
                    I18n.t("settings.download.versionList.title"),
                    I18n.t("settings.download.versionList.detail"),
                ) {
                    SimpleDropdown(
                        settings.effectiveVersionListSource,
                        listOf(
                            "auto" to I18n.t("settings.download.source.auto"),
                            "official" to I18n.t("settings.download.source.official"),
                            "bmclapi" to I18n.t("settings.download.source.bmclapi"),
                        ),
                        { value -> writer.commit { it.copy(versionListSource = value) } },
                        label = I18n.t("settings.download.versionList.title"),
                    )
                },
                settingField(
                    I18n.t("settings.download.fileSource.title"),
                    I18n.t("settings.download.fileSource.detail"),
                ) {
                    SimpleDropdown(
                        settings.effectiveDownloadSource,
                        listOf(
                            "auto" to I18n.t("settings.download.source.auto"),
                            "official" to I18n.t("settings.download.source.official"),
                            "bmclapi" to I18n.t("settings.download.source.bmclapi"),
                        ),
                        { value -> writer.commit { it.copy(downloadSource = value) } },
                        label = I18n.t("settings.download.fileSource.title"),
                    )
                },
                settingRow(
                    I18n.t("settings.download.threads.title"),
                    I18n.t("settings.download.threads.detail"),
                ) {
                    LiveNumberField(
                        settings.downloadThreads,
                        "",
                        1..MAX_DOWNLOAD_THREADS,
                        Modifier.width(120.dp),
                        placeholder = "64",
                        description = I18n.t("settings.download.threads.title"),
                    ) { value ->
                        applySetting(actions) { it.copy(downloadThreads = value) }
                    }
                },
                settingRow(
                    I18n.t("settings.download.segments.title"),
                    I18n.t("settings.download.segments.detail"),
                ) {
                    LiveNumberField(
                        settings.downloadSegments,
                        "",
                        1..MAX_DOWNLOAD_SEGMENTS,
                        Modifier.width(120.dp),
                        placeholder = DEFAULT_DOWNLOAD_SEGMENTS.toString(),
                    ) { value ->
                        applySetting(actions) { it.copy(downloadSegments = value) }
                    }
                },
                settingField(
                    I18n.t("settings.download.contentSource.title"),
                    I18n.t("settings.download.contentSource.detail"),
                ) {
                    SimpleDropdown(
                        settings.contentDownloadSource,
                        listOf("modrinth" to "Modrinth", "curseforge" to "CurseForge"),
                        { value -> writer.commit { it.copy(contentDownloadSource = value) } },
                    )
                },
                settingField(
                    I18n.t("settings.download.curseForge.title"),
                    I18n.t("settings.download.curseForge.detail"),
                ) {
                    LiveTextField(
                        settings.curseForgeBaseUrl,
                        { value ->
                            applySetting(actions) { it.copy(curseForgeBaseUrl = value.trim()) }
                        },
                    )
                },
                settingRow(
                    I18n.t("settings.download.timeout.title"),
                    I18n.t("settings.download.timeout.detail"),
                ) {
                    SimpleDropdown(
                        settings.networkTimeoutSeconds.toString(),
                        listOf(15, 30, 45, 60, 120, 180).map {
                            it.toString() to I18n.t("settings.download.timeout.seconds", it)
                        },
                        { value ->
                            writer.commit { it.copy(networkTimeoutSeconds = value.toInt()) }
                        },
                        Modifier.width(132.dp),
                    )
                },
                settingNote(I18n.t("settings.download.note")),
            ),
        ),
    )
    cards.add(
        SettingCard(
            I18n.t("settings.download.cache.title"),
            I18n.t("settings.download.cache.detail"),
            listOf(
                settingField(
                    I18n.t("settings.download.cache.path.title"),
                    I18n.t("settings.download.cache.path.detail"),
                ) {
                    LiveTextField(
                        settings.downloadCachePath,
                        { value -> applySetting(actions) { it.copy(downloadCachePath = value) } },
                        placeholder = I18n.t("settings.download.cache.placeholder"),
                        description = I18n.t("settings.download.cache.path.title"),
                    ) {
                        SmallIconButton(
                            Icons.Outlined.FolderOpen,
                            I18n.t("settings.download.cache.pick"),
                            {
                                actions.run {
                                    val chosen =
                                        actions
                                            .choose(
                                                I18n.t("settings.download.cache.choose"),
                                                directory = true,
                                                initialDirectory =
                                                    settings.downloadCachePath.takeIf { it.isNotBlank() }?.let(
                                                        Path::of,
                                                    ),
                                            ).firstOrNull()
                                    if (chosen != null) {
                                        withContext(Dispatchers.Swing) {
                                            applySetting(actions) {
                                                it.copy(downloadCachePath = chosen.toString())
                                            }
                                        }
                                    }
                                }
                            },
                        )
                    }
                },
            ),
        ),
    )
    cards.add(
        SettingCard(
            I18n.t("settings.download.proxy.title"),
            I18n.t("settings.download.proxy.detail"),
            listOf(
                settingRow(I18n.t("settings.download.proxy.mode.title")) {
                    SimpleDropdown(
                        settings.effectiveProxyMode(),
                        listOf(
                            "system" to I18n.t("settings.download.proxy.mode.system"),
                            "direct" to I18n.t("settings.download.proxy.mode.direct"),
                            "manual" to I18n.t("settings.download.proxy.mode.manual"),
                        ),
                        { value ->
                            writer.commit {
                                if (value != "manual") {
                                    it.copy(proxyMode = value)
                                } else {
                                    it.copy(
                                        proxyMode = "manual",
                                        // 手动代理必须有可用地址：未填写时先给一个可编辑的本地值，
                                        // 否则这次切换会被校验拒绝、下拉会跳回旧值。
                                        proxyHost = it.proxyHost.ifBlank { "127.0.0.1" },
                                        proxyPort =
                                            it.proxyPort.takeIf { port -> port in 1..65535 }
                                                ?: 1080,
                                    )
                                }
                            }
                        },
                        Modifier.width(150.dp),
                        label = I18n.t("settings.download.proxy.mode.title"),
                    )
                },
                settingField(
                    I18n.t("settings.download.proxy.type.title"),
                    I18n.t("settings.download.proxy.type.detail"),
                ) {
                    SimpleDropdown(
                        settings.proxyType,
                        listOf("http" to "HTTP", "socks" to "SOCKS"),
                        { value -> writer.commit { it.copy(proxyType = value) } },
                        enabled = settings.effectiveProxyMode() == "manual",
                    )
                },
                settingField(
                    I18n.t("settings.download.proxy.address.title"),
                    I18n.t("settings.download.proxy.address.detail"),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LiveTextField(
                            settings.proxyHost,
                            { value -> applySetting(actions) { it.copy(proxyHost = value) } },
                            Modifier.weight(1f),
                            placeholder = "127.0.0.1",
                            enabled = settings.effectiveProxyMode() == "manual",
                        )
                        LiveNumberField(
                            settings.proxyPort,
                            "",
                            0..65535,
                            Modifier.width(120.dp),
                            placeholder = I18n.t("settings.download.proxy.port"),
                            enabled = settings.effectiveProxyMode() == "manual",
                        ) { value ->
                            applySetting(actions) { it.copy(proxyPort = value) }
                        }
                    }
                },
            ),
        ),
    )
    return cards
}
