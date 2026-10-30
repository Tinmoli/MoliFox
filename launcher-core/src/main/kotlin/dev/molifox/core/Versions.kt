// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏版本、平台规则和版本清单转换。

package dev.molifox.core

/** Meta services do not all return loader releases in chronological order. */
fun compareLoaderVersions(
    left: String,
    right: String,
): Int {
    fun numericBase(value: String) =
        value
            .substringBefore('-')
            .substringBefore('+')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
    val leftBase = numericBase(left)
    val rightBase = numericBase(right)
    for (index in 0 until maxOf(leftBase.size, rightBase.size)) {
        val compared = leftBase.getOrElse(index) { 0 }.compareTo(rightBase.getOrElse(index) { 0 })
        if (compared != 0) return compared
    }
    val leftQualifier = left.substringBefore('+').substringAfter('-', "")
    val rightQualifier = right.substringBefore('+').substringAfter('-', "")
    if (leftQualifier.isBlank() != rightQualifier.isBlank()) {
        return if (leftQualifier.isBlank()) 1 else -1
    }
    val leftTokens = leftQualifier.split('.', '-')
    val rightTokens = rightQualifier.split('.', '-')
    for (index in 0 until maxOf(leftTokens.size, rightTokens.size)) {
        val a = leftTokens.getOrElse(index) { "" }
        val b = rightTokens.getOrElse(index) { "" }
        val compared =
            if (a.toIntOrNull() != null && b.toIntOrNull() != null) {
                a.toInt().compareTo(b.toInt())
            } else {
                a.compareTo(b)
            }
        if (compared != 0) return compared
    }
    return 0
}

/**
 * 可升级到的同加载器版本：只保留比当前加载器版本更新的条目，按从新到旧排列。
 *
 * [available] 必须已经是「同一 Minecraft 版本」的列表（来自 `loaderVersions(loader, gameVersion)`）；
 * 原版、未安装与整合包实例没有可升级项，因此不会出现换加载器或换游戏版本的入口。
 * 外部目录实例也在这里出现；它能否真正升级由 `MinecraftInstaller.externalLoaderUpgradeBlocker`
 * 按原 JSON 的继承链预检决定。
 */
fun loaderUpgradeCandidates(
    instance: GameInstance,
    available: List<LoaderVersion>,
): List<LoaderVersion> {
    val upgradeable =
        instance.loader != LoaderType.Vanilla &&
            instance.installed &&
            instance.packFile.isBlank() &&
            instance.loaderVersion.isNotBlank()
    if (!upgradeable) return emptyList()
    return available
        .filter { compareLoaderVersions(it.version, instance.loaderVersion) > 0 }
        .sortedWith { left, right -> compareLoaderVersions(right.version, left.version) }
}
