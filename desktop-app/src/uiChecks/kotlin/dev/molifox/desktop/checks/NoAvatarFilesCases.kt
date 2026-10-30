// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验收离线账户下访问开始页与光影页后，临时数据目录不落头像或模组图标缓存。

package dev.molifox.desktop.checks

import dev.molifox.core.AccountType
import java.nio.file.Files
import java.nio.file.Path

internal fun checkNoAvatarFiles(
    ui: UiProbe,
    fixture: UiFixture,
) {
    val accounts = fixture.controller.store.data.accounts
    check(accounts.isNotEmpty() && accounts.all { it.type == AccountType.Offline }) {
        "此场景需要离线账户，当前账户：$accounts"
    }
    ui.tab("开始", navigation = true)
    ui.await("开始页显示离线账户") {
        ui.exists("版本设置") && accounts.any { ui.exists(it.name) }
    }
    ui.click("版本设置")
    ui.await("实例详情") { ui.exists("管理") }
    ui.tab("光影")
    ui.await("光影页已渲染") { ui.exists("Aurora.zip") }
    val profile = fixture.platform.defaultDataDirectory
    val offending = avatarCacheEntries(profile)
    check(offending.isEmpty()) {
        "访问开始页与光影页后不应写头像缓存，实际存在：$offending"
    }
    check(!Files.exists(profile.resolve("icons/heads"))) { "不应创建 icons/heads 目录" }
    check(!Files.exists(profile.resolve("cache/mod-icons"))) { "不应创建 cache/mod-icons 目录" }
    ui.capture("no-avatar-files")
    ui.click("返回")
    ui.await("回到开始页") { ui.exists("版本设置") }
}

private fun avatarCacheEntries(root: Path): List<String> =
    if (!Files.isDirectory(root)) {
        emptyList()
    } else {
        Files.walk(root).use { paths ->
            paths
                .filter { path ->
                    val relative = root.relativize(path).toString().replace('\\', '/')
                    relative.startsWith("icons/heads") ||
                        relative.startsWith("cache/mod-icons")
                }.map { root.relativize(it).toString() }
                .toList()
        }
    }
