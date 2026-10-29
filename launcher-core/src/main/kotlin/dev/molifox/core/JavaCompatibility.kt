// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏及加载器与 Java 版本的兼容判断。

package dev.molifox.core

internal fun normalizedJavaArchitecture(value: String): String =
    when (value.lowercase()) {
        "amd64",
        "x86_64",
        "x64" -> "x64"
        "aarch64",
        "arm64" -> "arm64"
        "x86",
        "i386",
        "i486",
        "i586",
        "i686" -> "x86"
        else -> value.lowercase()
    }

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
    if (id.isEmpty()) return null
    // 允许带前缀的实例名（TwilightMC-26.3、fabric-loader-0.16.9-1.21.4）。
    Regex("(?<![0-9A-Za-z])(\\d{2})w(\\d{1,2})[a-z](?![0-9A-Za-z])", RegexOption.IGNORE_CASE)
        .find(id)
        ?.let { match ->
            val year = match.groupValues[1].toInt()
            val week = match.groupValues[2].toInt()
            return when {
                year >= 26 -> 25
                year == 25 -> 21
                year == 24 -> if (week >= 14) 21 else 17
                year >= 21 -> 17
                else -> 8
            }
        }
    // 容忍 26.3-pre1 / 26.3-rc1 / 1.20.5-pre2：只取开头的数字段。
    val headline = Regex("^\\d+(?:\\.\\d+)*").find(id)?.value ?: embeddedGameVersion(id)
    if (headline == null) return null
    val parts = headline.split('.').mapNotNull { it.toIntOrNull() }
    val major = parts.firstOrNull() ?: return null
    if (major >= 26) return 25
    if (major != 1) return null
    val minor = parts.getOrNull(1) ?: return 8
    val patch = parts.getOrNull(2) ?: 0
    return when {
        minor > 20 || (minor == 20 && patch >= 5) -> 21
        minor >= 17 -> 17
        else -> 8
    }
}

/** 从 `TwilightMC-26.3` 这类带前缀的 id 里取游戏版本号；跳过 0.x 加载器版本。 */
private fun embeddedGameVersion(id: String): String? =
    Regex("(?<![\\w.])(\\d{1,3}(?:\\.\\d+){1,3})(?![\\w.])")
        .findAll(id)
        .map { it.value }
        .firstOrNull { value ->
            val major = value.substringBefore('.').toIntOrNull()
            major != null && (major == 1 || major >= 26)
        }

/** 版本清单声明的下限与本机版本规则取较高者：例如 26.3 的清单仍写 21 时，实际要装 25。 */
fun requiredJavaMajor(gameVersion: String, declared: Int): Int =
    maxOf(gameJavaMajor(gameVersion) ?: declared, declared)

/** Metadata is a minimum; legacy LaunchWrapper additionally requires Java 8's class loader. */
internal data class JavaRequirement(
    val minimum: Int,
    val maximum: Int? = null,
    val exactAutomatic: Boolean = false,
) {
    fun problem(runtime: JavaRuntime, architecture: String): String? =
        when {
            normalizedJavaArchitecture(runtime.architecture) !=
                normalizedJavaArchitecture(architecture) ->
                "Java 架构 ${runtime.architecture.ifBlank { "未知" }} 与当前平台 $architecture 不匹配"
            runtime.majorVersion < minimum ->
                "游戏至少需要 Java $minimum，当前是 Java ${runtime.majorVersion}"
            maximum != null && runtime.majorVersion > maximum ->
                "此加载器只支持 Java $minimum–$maximum，当前是 Java ${runtime.majorVersion}"
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
                minimum <= 8 && libraries.any { it.startsWith("net.minecraft:launchwrapper:") }
            val modded = libraries.any {
                it.startsWith("net.fabricmc:fabric-loader:") ||
                    it.startsWith("org.quiltmc:quilt-loader:") ||
                    it.startsWith("net.minecraftforge:") ||
                    it.startsWith("net.neoforged:")
            }
            // Use the declared runtime for modded/old games unless the user explicitly overrides
            // it.
            return JavaRequirement(minimum, if (legacy) 8 else null, modded || minimum <= 8)
        }
    }
}
