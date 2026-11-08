// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责嵌入依赖（Jar-in-Jar）只读报告的公开模型。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 内嵌声明与模组元数据所属的加载器。 */
enum class JarInJarLoader {
    Fabric,
    Quilt,
    NeoForge,
    Forge,
    Legacy,
    Unknown,
    ;

    /** 显示名在读取时翻译；品牌名保持原样。 */
    val title: String
        get() =
            when (this) {
                Legacy -> I18n.t("core.jarInJarLoader.legacy")
                Unknown -> I18n.t("core.jarInJarLoader.unknown")
                else -> name
            }

    /** Forge 系的裸版本是 Maven 软下限，其余加载器按 SemVer 精确匹配。 */
    val forgeLike: Boolean
        get() = this == Forge || this == NeoForge
}

/** 一条依赖在实例里的 5 种状态。 */
enum class JarInJarStatus(
    private val titleKey: String,
) {
    Installed("core.jarInJarStatus.installed"),
    Disabled("core.jarInJarStatus.disabled"),
    Bundled("core.jarInJarStatus.bundled"),
    VersionMismatch("core.jarInJarStatus.versionMismatch"),
    Missing("core.jarInJarStatus.missing"),
    ;

    /** 显示名在读取时翻译，切换语言后重新取值。 */
    val title: String
        get() = I18n.t(titleKey)
}

/** 依赖声明里的一条记录；[constraint] 保留声明原文，空串表示不限版本。 */
data class JarInJarRequirement(
    val id: String,
    val constraint: String = "",
    val optional: Boolean = false,
)

/** 冲突声明：硬冲突会阻止加载，软冲突只是不推荐同时安装。 */
data class JarInJarConflict(
    val id: String,
    val constraint: String = "",
    val hard: Boolean = true,
    /** Forge 多 mod JAR 用 `[[dependencies.<modId>]]` 指定声明者；空串表示宿主自身。 */
    val declarerId: String = "",
)

/** 某个 JAR 额外提供的 mod id；Forge 兄弟 mod 与 Fabric `provides` 都会登记。 */
data class JarInJarProvided(
    val id: String,
    val version: String = "",
)

/** 内嵌 JAR 的只读节点；[path] 只是 zip 条目名，永不拼成文件系统路径。 */
data class EmbeddedJar(
    val path: String,
    val depth: Int,
    val loader: JarInJarLoader = JarInJarLoader.Unknown,
    val modId: String = "",
    val name: String = "",
    val version: String = "",
    /** 声明的 Minecraft 约束原文；空串表示不限。 */
    val targetMc: String = "",
    val dependencies: List<JarInJarRequirement> = emptyList(),
    val conflicts: List<JarInJarConflict> = emptyList(),
    val provides: List<JarInJarProvided> = emptyList(),
    /** Forge/NeoForge JarJar 的 `group:artifact`；同组多版本只保留可加载的一份。 */
    val identifier: String = "",
    val versionRange: String = "",
    val artifactVersion: String = "",
    val children: List<EmbeddedJar> = emptyList(),
    /** false 表示该节点内部解析失败、超限或未再向下解析。 */
    val complete: Boolean = true,
) {
    val displayName: String
        get() = name.ifBlank { modId.ifBlank { path.substringAfterLast('/') } }

    val descendantCount: Int
        get() = children.size + children.sumOf { it.descendantCount }
}

/** 一个顶层模组（宿主 JAR）的只读声明与内嵌树。 */
data class JarInJarHost(
    val filename: String,
    val enabled: Boolean,
    val loader: JarInJarLoader = JarInJarLoader.Unknown,
    val modId: String = "",
    val name: String = "",
    val version: String = "",
    val dependencies: List<JarInJarRequirement> = emptyList(),
    val conflicts: List<JarInJarConflict> = emptyList(),
    /** 自身的其它 mod id（Forge 兄弟 mod、Fabric/Quilt `provides`）。 */
    val provides: List<JarInJarProvided> = emptyList(),
    val embedded: List<EmbeddedJar> = emptyList(),
    /** false 表示该宿主的内嵌树没有解析完整，缺失类结论需要降级。 */
    val complete: Boolean = true,
    /** 无法解析的原因；只含文件名与错误类型，不含用户目录。 */
    val note: String = "",
) {
    val embeddedCount: Int
        get() = embedded.sumOf { it.descendantCount + 1 }

    val displayName: String
        get() = name.ifBlank { modId.ifBlank { filename } }
}

/** 依赖关系区的一行：某个宿主或内嵌节点声明的依赖及其当前状态。 */
data class JarInJarDependencyRow(
    val hostFilename: String,
    /** 声明者：宿主文件名或内嵌节点路径。 */
    val declarer: String,
    val embedded: Boolean,
    val loader: JarInJarLoader,
    val dependency: JarInJarRequirement,
    val status: JarInJarStatus,
    /** 同 id 提供者的版本（不含声明者自己），用于版本不符与禁用提示。 */
    val providerVersions: List<String> = emptyList(),
)

/** 需要用户处理的依赖项；Phase 1 只展示，不安装也不忽略。 */
data class JarInJarWarning(
    /** 稳定标识：`宿主|依赖 id|约束`，大小写不敏感；供后续忽略功能使用。 */
    val key: String,
    val hostFilename: String,
    val declarer: String,
    val dependencyId: String,
    val constraint: String,
    val optional: Boolean,
    val status: JarInJarStatus,
    /** 到内容源搜索缺失依赖时使用的关键词。 */
    val searchTerms: List<String> = emptyList(),
    val providerVersions: List<String> = emptyList(),
)

/** 实际生效的冲突关系：声明方与对方都启用，且对方版本落在冲突范围内。 */
data class JarInJarConflictPair(
    val hard: Boolean,
    val declarer: String,
    val declarerHost: String,
    val targetId: String,
    val target: String,
    val targetHost: String,
    val constraint: String = "",
)

/** 一个实例的嵌入依赖只读报告；[incomplete] 为真时缺失类结论不可当作定论。 */
data class JarInJarReport(
    val instanceId: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val hosts: List<JarInJarHost> = emptyList(),
    val rows: List<JarInJarDependencyRow> = emptyList(),
    val warnings: List<JarInJarWarning> = emptyList(),
    val conflicts: List<JarInJarConflictPair> = emptyList(),
    val incomplete: Boolean = false,
    val incompleteNote: String = "",
) {
    val embeddedCount: Int
        get() = hosts.sumOf { it.embeddedCount }

    /** 待处理项数量；不完整时缺失/版本不符已抑制，所以这个数字偏保守。 */
    val pendingCount: Int
        get() = warnings.size

    val hasContent: Boolean
        get() = embeddedCount > 0 || rows.isNotEmpty() || conflicts.isNotEmpty()
}

/** 实例加载器转成声明方言；原版实例没有方言，留空交给约束语法特征识别。 */
internal fun LoaderType.jarInJarLoader(): JarInJarLoader =
    when (this) {
        LoaderType.Fabric,
        LoaderType.LegacyFabric,
        -> JarInJarLoader.Fabric
        LoaderType.Quilt -> JarInJarLoader.Quilt
        LoaderType.Forge -> JarInJarLoader.Forge
        LoaderType.NeoForge -> JarInJarLoader.NeoForge
        LoaderType.Vanilla,
        LoaderType.OptiFine,
        LoaderType.LiteLoader,
        LoaderType.Cleanroom,
        -> JarInJarLoader.Unknown
    }
