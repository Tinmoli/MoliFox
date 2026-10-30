// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中下载服务、并发与超时选项，保持配置入口一致。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.core.DEFAULT_CURSE_FORGE_BASE_URL
import dev.molifox.core.LauncherSettings
import dev.molifox.core.effectiveDownloadSource
import dev.molifox.core.effectiveProxyMode
import dev.molifox.core.effectiveVersionListSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 下载：下载源、并发数、缓存文件夹与代理。 */
@Composable
internal fun downloadSettingCards(
    actions: UiActions,
    writer: SettingsWriter,
    settings: LauncherSettings,
): List<SettingCard> {
    val cards = mutableListOf<SettingCard>()
    cards.add(
        SettingCard(
            "下载设置",
            "自动模式在中国网络优先镜像；分段固定关闭、失败换源重试 5 次。",
            listOf(
                settingField("版本列表源", "只影响版本清单（manifest）的获取地址。") {
                    SimpleDropdown(
                        settings.effectiveVersionListSource,
                        listOf(
                            "auto" to "自动",
                            "official" to "官方",
                            "bmclapi" to "国内镜像（BMCLAPI）",
                        ),
                        { value -> writer.commit { it.copy(versionListSource = value) } },
                    )
                },
                settingField("文件下载源", "影响本体、依赖库、资源文件与 Java。") {
                    SimpleDropdown(
                        settings.effectiveDownloadSource,
                        listOf(
                            "auto" to "自动",
                            "official" to "官方",
                            "bmclapi" to "国内镜像（BMCLAPI）",
                        ),
                        { value -> writer.commit { it.copy(downloadSource = value) } },
                    )
                },
                settingRow("下载线程数", "同时下载的文件数，1–128；下一个任务生效。") {
                    LiveNumberField(
                        settings.downloadThreads,
                        "",
                        1..128,
                        Modifier.width(120.dp),
                        placeholder = "64",
                    ) { value ->
                        applySetting(actions) { it.copy(downloadThreads = value) }
                    }
                },
                settingField("附加内容下载源", "选择 Modrinth 或 CurseForge。") {
                    SimpleDropdown(
                        settings.contentDownloadSource,
                        listOf("modrinth" to "Modrinth", "curseforge" to "CurseForge"),
                        { value -> writer.commit { it.copy(contentDownloadSource = value) } },
                    )
                },
                settingField("CurseForge 服务", "公共镜像或自建代理地址；留空使用默认服务。") {
                    LiveTextField(
                        settings.curseForgeBaseUrl,
                        { value ->
                            applySetting(actions) { it.copy(curseForgeBaseUrl = value.trim()) }
                        },
                        placeholder = DEFAULT_CURSE_FORGE_BASE_URL,
                    )
                },
                settingRow("网络超时", "新请求的读取超时；代理和分段设置无需重启。") {
                    SimpleDropdown(
                        settings.networkTimeoutSeconds.toString(),
                        listOf(15, 30, 45, 60, 120, 180).map { it.toString() to "$it 秒" },
                        { value ->
                            writer.commit { it.copy(networkTimeoutSeconds = value.toInt()) }
                        },
                        Modifier.width(132.dp),
                    )
                },
                settingNote("加载器资源使用各自官方服务。可同时添加下载；取消任务会清理本次下载的临时文件。"),
            ),
        ),
    )
    cards.add(
        SettingCard(
            "缓存文件夹",
            "版本列表与安装包的缓存位置，留空使用 MoliFox/cache。",
            listOf(
                settingField("缓存文件夹", "绝对路径；目录不存在时会自动创建。") {
                    LiveTextField(
                        settings.downloadCachePath,
                        { value -> applySetting(actions) { it.copy(downloadCachePath = value) } },
                        placeholder = "留空使用默认缓存目录",
                    ) {
                        SmallIconButton(
                            Icons.Outlined.FolderOpen,
                            "选择缓存文件夹",
                            {
                                actions.run {
                                    val chosen =
                                        actions.choose("选择下载缓存文件夹", directory = true).firstOrNull()
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
            "代理",
            "改动立即作用于启动器下载、资源搜索和登录。",
            listOf(
                settingRow("连接方式") {
                    SimpleDropdown(
                        settings.effectiveProxyMode(),
                        listOf(
                            "system" to "跟随系统",
                            "direct" to "直接连接",
                            "manual" to "手动代理",
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
                    )
                },
                settingField("代理类型", "仅手动代理时可用。") {
                    SimpleDropdown(
                        settings.proxyType,
                        listOf("http" to "HTTP", "socks" to "SOCKS"),
                        { value -> writer.commit { it.copy(proxyType = value) } },
                        enabled = settings.effectiveProxyMode() == "manual",
                    )
                },
                settingField("代理地址", "主机与端口；仅手动代理时生效。") {
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
                            placeholder = "端口",
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
