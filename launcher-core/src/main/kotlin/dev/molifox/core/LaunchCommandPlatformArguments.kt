// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/**
 * 平台差异 JVM 参数：32 位 JVM 的栈上限与 macOS 的首线程、Dock 名称。
 * 1.13 前的官方模板不含 `-XstartOnFirstThread`，旧版 LWJGL 缺它在 macOS 上必然闪退；
 * 新模板已按规则给出该参数，按模板原样判断避免重复。
 */
internal fun platformJvmArguments(
    platform: PlatformServices,
    template: List<String>,
    profile: ResolvedProfile,
): List<String> {
    val arguments = mutableListOf<String>()
    if (platform.architecture == "x86") arguments += "-Xss1m"
    if (platform.osName == "osx") {
        if ("-XstartOnFirstThread" !in template) arguments += "-XstartOnFirstThread"
        arguments += "-Xdock:name=Minecraft ${profile.id}"
    }
    return arguments
}

/** ARM64 设备跑 1.19 前的版本没有官方原生库：启动前说明，而不是等游戏自己崩在加载库上。 */
internal fun armNativesWarning(
    platform: PlatformServices,
    profile: ResolvedProfile,
): List<String> =
    if (platform.architecture == "arm64" && isLegacyNativesVersion(profile.jarId)) {
        listOf(I18n.t("core.gameLauncher.30", profile.jarId))
    } else {
        emptyList()
    }
