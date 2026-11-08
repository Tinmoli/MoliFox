// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责识别真实的 Minecraft 版本号，避免把目录名或整合包版本当成版本筛选值。

package dev.molifox.core

/**
 * 版本号本体：`26.3`、`1.21.8`、`1.20`，以及带 `-snapshot`/`-pre`/`-rc` 后缀的预发布形式。
 * 首段限制一到两位数字，因此加载器版本（`0.19.5`）不会被当成游戏版本。
 */
private val minecraftVersionTagPattern =
    Regex("""^[1-9]\d{0,1}\.\d{1,2}(?:\.\d{1,2})?(?:-(?:snapshot|preview|pre|rc)[-.]?\d*)?$""")

/** 从任意文本里取第一个独立的版本号，两侧不能再是数字或点。 */
private val minecraftVersionTokenPattern =
    Regex("""(?<![\d.])([1-9]\d{0,1}\.\d{1,2}(?:\.\d{1,2})?)(?![\d.])""")

/** 这个值本身就是一个 Minecraft 版本号（可直接作为 CurseForge / Modrinth 的版本筛选）。 */
fun isMinecraftVersionTag(value: String): Boolean = minecraftVersionTagPattern.matches(value.trim())

/**
 * 把实例版本目录名之类的标识解析成真实的游戏版本：`TwilightMC-26.3v1.0` → `26.3`。
 * 真实版本号原样返回；解析不到返回 null，调用方应放弃版本筛选并记录日志，而不是把整串标识发给服务端。
 */
fun minecraftVersionTag(value: String): String? {
    val text = value.trim()
    if (text.isEmpty()) return null
    if (isMinecraftVersionTag(text)) return text
    return minecraftVersionTokenPattern.find(text)?.groupValues?.get(1)
}
