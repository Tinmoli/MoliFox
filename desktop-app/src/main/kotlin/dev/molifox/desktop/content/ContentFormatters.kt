// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 内容浏览的文本辅助：类型名、Modrinth 分类、下载量与内容来源名称。

package dev.molifox.desktop.content

internal fun contentKindLabel(type: String): String =
    when (type) {
        "modpack" -> "整合包"
        "resourcepack" -> "资源包"
        "shader" -> "光影"
        "datapack" -> "数据包"
        else -> "模组"
    }

/**
 * Modrinth 分类（slug + 中文名），按项目类型分开维护：slug 全部取自 Modrinth 标签接口 `/v2/tag/category` 中该 `project_type`
 * 的条目，顺序沿用官方标签页的分组 （模组；资源包：分辨率 → 分类 → 特性；光影：分类 → 特性 → 性能；等等）。
 */
private val modCategories =
    listOf(
        "technology" to "科技",
        "magic" to "魔法",
        "adventure" to "冒险",
        "decoration" to "装饰",
        "worldgen" to "世界生成",
        "mobs" to "生物",
        "equipment" to "装备",
        "food" to "食物",
        "optimization" to "优化",
        "storage" to "存储",
        "utility" to "实用",
        "library" to "库",
        "transportation" to "交通",
        "game-mechanics" to "游戏机制",
        "economy" to "经济",
        "management" to "管理",
        "social" to "社交",
        "minigame" to "小游戏",
        "cursed" to "诅咒",
    )

/** 数据包在 Modrinth 上复用模组分类（其项目类型以 datapack 加载器标记），因此单独列一份便于以后分化。 */
private val datapackCategories = modCategories

private val modpackCategories =
    listOf(
        "adventure" to "冒险",
        "challenging" to "挑战",
        "combat" to "战斗",
        "kitchen-sink" to "大杂烩",
        "lightweight" to "轻量",
        "magic" to "魔法",
        "multiplayer" to "多人",
        "optimization" to "优化",
        "quests" to "任务",
        "technology" to "科技",
    )

private val resourcePackCategories =
    listOf(
        "8x-" to "8x 及以下",
        "16x" to "16x",
        "32x" to "32x",
        "48x" to "48x",
        "64x" to "64x",
        "128x" to "128x",
        "256x" to "256x",
        "combat" to "战斗",
        "cursed" to "诅咒",
        "decoration" to "装饰",
        "modded" to "模组兼容",
        "realistic" to "写实",
        "simplistic" to "简洁",
        "themed" to "主题",
        "tweaks" to "微调",
        "utility" to "实用",
        "vanilla-like" to "原版风格",
        "audio" to "音效",
        "blocks" to "方块",
        "entities" to "实体",
        "environment" to "环境",
        "equipment" to "装备",
        "fonts" to "字体",
        "gui" to "界面",
        "items" to "物品",
        "locale" to "语言",
        "models" to "模型",
        "core-shaders" to "核心光影",
    )

private val shaderCategories =
    listOf(
        "cartoon" to "卡通",
        "cursed" to "诅咒",
        "fantasy" to "奇幻",
        "realistic" to "写实",
        "semi-realistic" to "半写实",
        "vanilla-like" to "原版风格",
        "atmosphere" to "氛围",
        "bloom" to "泛光",
        "colored-lighting" to "彩色光照",
        "foliage" to "植被",
        "path-tracing" to "路径追踪",
        "pbr" to "PBR 材质",
        "reflections" to "反射",
        "shadows" to "阴影",
        "screenshot" to "适合截图",
        "low" to "低配",
        "medium" to "中配",
        "high" to "高配",
        "potato" to "极低配",
    )

/** 当前类型的分类列表；类型已由左侧导航决定，分类只在该类型真实存在的标签里选。 */
internal fun categoriesFor(kind: String): List<Pair<String, String>> =
    when (kind) {
        "resourcepack" -> resourcePackCategories
        "shader" -> shaderCategories
        "datapack" -> datapackCategories
        "modpack" -> modpackCategories
        else -> modCategories
    }

internal fun formatDownloads(value: Long): String =
    when {
        value >= 1_000_000 -> "%.1fM".format(value / 1_000_000f)
        value >= 1_000 -> "%.1fk".format(value / 1_000f)
        else -> value.toString()
    }

/**
 * 内容源显示名：只把真实的配置值/项目 ID 映射为官方名称，其余回退中性文案「内容来源」。 与 core 的 `ContentDetails.providerName`
 * 判定一致（CurseForge 项目带 `curseforge:` 前缀）。
 */
internal fun contentSourceName(value: String?): String =
    when {
        value.isNullOrBlank() -> "内容来源"
        value.equals("curseforge", true) || value.startsWith("curseforge:", true) -> "CurseForge"
        value.equals("modrinth", true) || value.startsWith("modrinth:", true) -> "Modrinth"
        else -> "内容来源"
    }

/** 内容源主页；只为真实存在的内容源提供入口，未知来源返回 null（不伪造地址）。 */
internal fun contentSourceHome(value: String?): String? =
    when (contentSourceName(value)) {
        "Modrinth" -> "https://modrinth.com"
        "CurseForge" -> "https://www.curseforge.com/minecraft"
        else -> null
    }
