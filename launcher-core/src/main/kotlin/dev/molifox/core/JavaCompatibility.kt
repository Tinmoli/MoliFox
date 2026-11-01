// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏及加载器与 Java 版本的兼容判断。

package dev.molifox.core

import dev.molifox.core.i18n.I18n

/** 需要安装/校验的 Java 主版本；名称即版本号，避免在规则表里散落裸数字。 */
private const val JAVA_MAJOR_8 = 8
private const val JAVA_MAJOR_17 = 17
private const val JAVA_MAJOR_21 = 21
private const val JAVA_MAJOR_25 = 25

/** 2026 版本线（26.x 与 26w 快照）的分界。 */
private const val VERSION_YEAR_2026 = 26

/** 快照 ID 的两位年份与「从该周起按新 Java」的周次分界。 */
private const val SNAPSHOT_YEAR_2021 = 21
private const val SNAPSHOT_YEAR_2024 = 24
private const val SNAPSHOT_YEAR_2025 = 25
private const val SNAPSHOT_JAVA_21_WEEK = 14

/** 1.20.5（快照 24w14a）起官方改用 Java 21 的游戏小版本与补丁号分界。 */
private const val GAME_MINOR_20 = 20
private const val GAME_PATCH_5 = 5

internal fun normalizedJavaArchitecture(value: String): String =
    when (value.lowercase()) {
        "amd64",
        "x86_64",
        "x64",
        -> "x64"
        "aarch64",
        "arm64",
        -> "arm64"
        "x86",
        "i386",
        "i486",
        "i586",
        "i686",
        -> "x86"
        else -> value.lowercase()
    }

/** 远古 Alpha/Beta 版本 ID：a1.2.6、b1.7.3、c0.30 等，统一按 Java 8 处理。 */
private val LEGACY_VERSION_ID = Regex("^[abc]\\d+(?:\\.\\d+)+")

/**
 * 游戏版本 → 游戏 Java 主版本。规则来源：
 * - ≤ 1.16.5：Mojang version manifest（piston-meta）的 `javaVersion.majorVersion` 为 8；
 * - 1.17 – 1.20.4：1.18 起官方清单要求 17；1.17 官方为 16，本启动器按 2026-10-29 方案统一取 17；
 * - 1.20.5 – 1.21.x：官方从 1.20.5（快照 24w14a）起改为 21（Minecraft Wiki「Java Edition 1.20.5」）；
 * - ≥ 26.0：2026 版本线（如 26.3 / 26.3-pre1）按用户确认需要 25；TwilightMC-26.3 实测按 25 运行。
 *
 * 快照 ID（24w14a、25w31a、26w01a）按年份/周次换算：24w14a 起 21，更早的 24w 仍是 17， 25w 为 21，26w 起为 25；更早的快照按 1.x
 * 年代归入 17 或 8。
 */
fun gameJavaMajor(gameVersion: String): Int? {
    val id = gameVersion.trim()
    return if (id.isEmpty()) {
        null
    } else if (LEGACY_VERSION_ID.containsMatchIn(id)) {
        JAVA_MAJOR_8
    } else {
        snapshotJavaMajor(id) ?: headlineJavaMajor(id)
    }
}

/**
 * 快照 ID（24w14a、25w31a、26w01a）按年份/周次换算：24w14a 起 21，更早的 24w 仍是 17，25w 为 21，
 * 26w 起为 25；更早的快照按 1.x 年代归入 17 或 8。不是快照时返回 null。
 */
private fun snapshotJavaMajor(id: String): Int? {
    // 允许带前缀的实例名（TwilightMC-26.3、fabric-loader-0.16.9-1.21.4）。
    val snapshot =
        Regex("(?<![0-9A-Za-z])(\\d{2})w(\\d{1,2})[a-z](?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
            .find(id) ?: return null
    val year = snapshot.groupValues[1].toInt()
    val week = snapshot.groupValues[2].toInt()
    return when {
        year >= VERSION_YEAR_2026 -> JAVA_MAJOR_25
        year == SNAPSHOT_YEAR_2025 -> JAVA_MAJOR_21
        year == SNAPSHOT_YEAR_2024 ->
            if (week >= SNAPSHOT_JAVA_21_WEEK) JAVA_MAJOR_21 else JAVA_MAJOR_17
        year >= SNAPSHOT_YEAR_2021 -> JAVA_MAJOR_17
        else -> JAVA_MAJOR_8
    }
}

/** 数字版本号换算；容忍 26.3-pre1 / 26.3-rc1 / 1.20.5-pre2：只取开头的数字段。 */
private fun headlineJavaMajor(id: String): Int? {
    val headline = Regex("^\\d+(?:\\.\\d+)*").find(id)?.value ?: embeddedGameVersion(id)
    if (headline == null) return null
    val parts = headline.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.firstOrNull()
    val minor = parts.getOrNull(1)
    return when {
        major == null -> null
        major >= VERSION_YEAR_2026 -> JAVA_MAJOR_25
        major != 1 -> null
        minor == null -> JAVA_MAJOR_8
        minor > GAME_MINOR_20 ||
            (minor == GAME_MINOR_20 && (parts.getOrNull(2) ?: 0) >= GAME_PATCH_5) ->
            JAVA_MAJOR_21
        minor >= JAVA_MAJOR_17 -> JAVA_MAJOR_17
        else -> JAVA_MAJOR_8
    }
}

/** 从 `TwilightMC-26.3` 这类带前缀的 id 里取游戏版本号；跳过 0.x 加载器版本。 */
private fun embeddedGameVersion(id: String): String? =
    Regex("(?<![\\w.])(\\d{1,3}(?:\\.\\d+){1,3})(?![\\w.])")
        .findAll(id)
        .map { it.value }
        .firstOrNull { value ->
            val major = value.substringBefore('.').toIntOrNull()
            major != null && (major == 1 || major >= VERSION_YEAR_2026)
        }

/** 版本清单声明的下限与本机版本规则取较高者：例如 26.3 的清单仍写 21 时，实际要装 25。 */
fun requiredJavaMajor(
    gameVersion: String,
    declared: Int,
): Int = maxOf(gameJavaMajor(gameVersion) ?: declared, declared)

/** Metadata is a minimum; legacy LaunchWrapper additionally requires Java 8's class loader. */
internal data class JavaRequirement(
    val minimum: Int,
    val maximum: Int? = null,
    val exactAutomatic: Boolean = false,
) {
    fun problem(
        runtime: JavaRuntime,
        architecture: String,
    ): String? =
        when {
            normalizedJavaArchitecture(runtime.architecture) !=
                normalizedJavaArchitecture(architecture) ->
                I18n.t(
                    "networkB1.javaCompatibility.architectureMismatch",
                    runtime.architecture.ifBlank { I18n.t("dialogs.common.unknown") },
                    architecture,
                )
            runtime.majorVersion < minimum ->
                I18n.t("networkB1.javaCompatibility.minimumRequired", minimum, runtime.majorVersion)
            maximum != null && runtime.majorVersion > maximum ->
                I18n.t(
                    "networkB1.javaCompatibility.rangeSupported",
                    minimum,
                    maximum,
                    runtime.majorVersion,
                )
            else -> null
        }

    companion object {
        fun from(profile: ResolvedProfile): JavaRequirement {
            val libraries = profile.json.array("libraries").map { it.asJsonObject.string("name") }
            val gameVersion =
                listOf("clientVersion", "_minecraftVersion", "inheritsFrom", "id")
                    .map { profile.json.string(it) }
                    .firstOrNull { gameJavaMajor(it) != null }
                    .orEmpty()
            val minimum = requiredJavaMajor(gameVersion, profile.requiredJava)
            val legacy =
                minimum <= JAVA_MAJOR_8 &&
                    libraries.any { it.startsWith("net.minecraft:launchwrapper:") }
            val modded =
                libraries.any {
                    it.startsWith("net.fabricmc:fabric-loader:") ||
                        it.startsWith("org.quiltmc:quilt-loader:") ||
                        it.startsWith("net.minecraftforge:") ||
                        it.startsWith("net.neoforged:")
                }
            // Use the declared runtime for modded/old games unless the user explicitly overrides
            // it.
            return JavaRequirement(
                minimum,
                if (legacy) JAVA_MAJOR_8 else null,
                modded || minimum <= JAVA_MAJOR_8,
            )
        }
    }
}
