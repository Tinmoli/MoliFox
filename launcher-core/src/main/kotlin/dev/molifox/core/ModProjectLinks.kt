// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 由 jar 内声明里的项目链接推出可打开的站点页，供本地模组详情显示。

package dev.molifox.core

/**
 * 把声明里的 Modrinth / CurseForge 项目引用换成项目页链接。
 *
 * 只做字符串拼接、不联网：解析不到项目引用的模组返回空列表，界面据此完全不显示链接行。
 */
fun projectLinks(refs: List<ModProjectRef>): List<ContentLink> =
    refs
        .mapNotNull { ref ->
            when (ref.provider.lowercase()) {
                "modrinth" -> ContentLink("Modrinth", "https://modrinth.com/mod/${ref.slug}")

                "curseforge" ->
                    ContentLink(
                        "CurseForge",
                        "https://www.curseforge.com/minecraft/mc-mods/${ref.slug}",
                    )

                else -> null
            }
        }.distinctBy { it.url }
