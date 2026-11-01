// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容浏览的文本辅助：类型名、Modrinth 分类、下载量与内容来源名称。

package dev.molifox.desktop.content

import dev.molifox.desktop.tr

private const val MILLION_COUNT = 1_000_000
private const val MILLION_DIVISOR = 1_000_000f
private const val THOUSAND_COUNT = 1_000
private const val THOUSAND_DIVISOR = 1_000f

/** 内容类型显示名；类型 slug 本身不翻译，只把已知 slug 映射到文案 key。 */
internal fun contentKindLabel(type: String): String =
    when (type) {
        "modpack" -> tr("pages.content.kind.modpack")
        "resourcepack" -> tr("pages.content.kind.resourcepack")
        "shader" -> tr("pages.content.kind.shader")
        "datapack" -> tr("pages.content.kind.datapack")
        "world" -> tr("pages.content.kind.world")
        else -> tr("pages.content.kind.mod")
    }

/**
 * Modrinth 分类（slug + 文案 key），按项目类型分开维护：slug 全部取自 Modrinth 标签接口 `/v2/tag/category` 中该 `project_type`
 * 的条目，顺序沿用官方标签页的分组 （模组；资源包：分辨率 → 分类 → 特性；光影：分类 → 特性 → 性能；等等）。
 */
private fun modCategories(): List<Pair<String, String>> =
    listOf(
        "technology" to tr("pages.content.tag.technology"),
        "magic" to tr("pages.content.tag.magic"),
        "adventure" to tr("pages.content.tag.adventure"),
        "decoration" to tr("pages.content.tag.decoration"),
        "worldgen" to tr("pages.content.tag.worldgen"),
        "mobs" to tr("pages.content.tag.mobs"),
        "equipment" to tr("pages.content.tag.equipment"),
        "food" to tr("pages.content.tag.food"),
        "optimization" to tr("pages.content.tag.optimization"),
        "storage" to tr("pages.content.tag.storage"),
        "utility" to tr("pages.content.tag.utility"),
        "library" to tr("pages.content.tag.library"),
        "transportation" to tr("pages.content.tag.transportation"),
        "game-mechanics" to tr("pages.content.tag.gameMechanics"),
        "economy" to tr("pages.content.tag.economy"),
        "management" to tr("pages.content.tag.management"),
        "social" to tr("pages.content.tag.social"),
        "minigame" to tr("pages.content.tag.minigame"),
        "cursed" to tr("pages.content.tag.cursed"),
    )

/** 数据包在 Modrinth 上复用模组分类（其项目类型以 datapack 加载器标记），因此单独列一份便于以后分化。 */
private fun datapackCategories(): List<Pair<String, String>> = modCategories()

private fun modpackCategories(): List<Pair<String, String>> =
    listOf(
        "adventure" to tr("pages.content.tag.adventure"),
        "challenging" to tr("pages.content.tag.challenging"),
        "combat" to tr("pages.content.tag.combat"),
        "kitchen-sink" to tr("pages.content.tag.kitchenSink"),
        "lightweight" to tr("pages.content.tag.lightweight"),
        "magic" to tr("pages.content.tag.magic"),
        "multiplayer" to tr("pages.content.tag.multiplayer"),
        "optimization" to tr("pages.content.tag.optimization"),
        "quests" to tr("pages.content.tag.quests"),
        "technology" to tr("pages.content.tag.technology"),
    )

private fun resourcePackCategories(): List<Pair<String, String>> =
    listOf(
        "8x-" to tr("pages.content.tag.pixel8x"),
        // 分辨率标签是纯数字 + x，各语言一致，不做翻译。
        "16x" to "16x",
        "32x" to "32x",
        "48x" to "48x",
        "64x" to "64x",
        "128x" to "128x",
        "256x" to "256x",
        "combat" to tr("pages.content.tag.combat"),
        "cursed" to tr("pages.content.tag.cursed"),
        "decoration" to tr("pages.content.tag.decoration"),
        "modded" to tr("pages.content.tag.modded"),
        "realistic" to tr("pages.content.tag.realistic"),
        "simplistic" to tr("pages.content.tag.simplistic"),
        "themed" to tr("pages.content.tag.themed"),
        "tweaks" to tr("pages.content.tag.tweaks"),
        "utility" to tr("pages.content.tag.utility"),
        "vanilla-like" to tr("pages.content.tag.vanillaLike"),
        "audio" to tr("pages.content.tag.audio"),
        "blocks" to tr("pages.content.tag.blocks"),
        "entities" to tr("pages.content.tag.entities"),
        "environment" to tr("pages.content.tag.environment"),
        "equipment" to tr("pages.content.tag.equipment"),
        "fonts" to tr("pages.content.tag.fonts"),
        "gui" to tr("pages.content.tag.gui"),
        "items" to tr("pages.content.tag.items"),
        "locale" to tr("pages.content.tag.locale"),
        "models" to tr("pages.content.tag.models"),
        "core-shaders" to tr("pages.content.tag.coreShaders"),
    )

private fun shaderCategories(): List<Pair<String, String>> =
    listOf(
        "cartoon" to tr("pages.content.tag.cartoon"),
        "cursed" to tr("pages.content.tag.cursed"),
        "fantasy" to tr("pages.content.tag.fantasy"),
        "realistic" to tr("pages.content.tag.realistic"),
        "semi-realistic" to tr("pages.content.tag.semiRealistic"),
        "vanilla-like" to tr("pages.content.tag.vanillaLike"),
        "atmosphere" to tr("pages.content.tag.atmosphere"),
        "bloom" to tr("pages.content.tag.bloom"),
        "colored-lighting" to tr("pages.content.tag.coloredLighting"),
        "foliage" to tr("pages.content.tag.foliage"),
        "path-tracing" to tr("pages.content.tag.pathTracing"),
        "pbr" to tr("pages.content.tag.pbr"),
        "reflections" to tr("pages.content.tag.reflections"),
        "shadows" to tr("pages.content.tag.shadows"),
        "screenshot" to tr("pages.content.tag.screenshot"),
        "low" to tr("pages.content.tag.low"),
        "medium" to tr("pages.content.tag.medium"),
        "high" to tr("pages.content.tag.high"),
        "potato" to tr("pages.content.tag.potato"),
    )

/** 当前类型的分类列表；类型已由左侧导航决定，分类只在该类型真实存在的标签里选。 */
internal fun categoriesFor(kind: String): List<Pair<String, String>> =
    when (kind) {
        "resourcepack" -> resourcePackCategories()
        "shader" -> shaderCategories()
        "datapack" -> datapackCategories()
        "modpack" -> modpackCategories()
        else -> modCategories()
    }

internal fun formatDownloads(value: Long): String =
    when {
        value >= MILLION_COUNT -> "%.1fM".format(value / MILLION_DIVISOR)
        value >= THOUSAND_COUNT -> "%.1fk".format(value / THOUSAND_DIVISOR)
        else -> value.toString()
    }

/**
 * 内容源显示名：只把真实的配置值/项目 ID 映射为官方名称，其余回退中性文案「内容来源」。 与 core 的 `ContentDetails.providerName`
 * 判定一致（CurseForge 项目带 `curseforge:` 前缀）。
 */
internal fun contentSourceName(value: String?): String =
    when {
        value.isNullOrBlank() -> tr("pages.content.source.unknown")
        value.equals("curseforge", true) || value.startsWith("curseforge:", true) -> "CurseForge"
        value.equals("modrinth", true) || value.startsWith("modrinth:", true) -> "Modrinth"
        else -> tr("pages.content.source.unknown")
    }

/** 内容源主页；只为真实存在的内容源提供入口，未知来源返回 null（不伪造地址）。 */
internal fun contentSourceHome(value: String?): String? =
    when {
        value.isNullOrBlank() -> null
        value.equals("curseforge", true) ||
            value.startsWith("curseforge:", true) -> "https://www.curseforge.com/minecraft"
        value.equals("modrinth", true) || value.startsWith("modrinth:", true) -> "https://modrinth.com"
        else -> null
    }
