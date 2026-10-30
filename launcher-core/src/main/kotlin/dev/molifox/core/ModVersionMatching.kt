// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中版本约束和版本排序规则，避免不同来源使用不同的比较方式。

package dev.molifox.core

private val remoteVersionToken =
    Regex("""(?i)(?<![\w.])(v?\d+(?:\.\d+)+(?:[-+][0-9a-z][0-9a-z.+_-]*)?)(?![\w.])""")

/**
 * CurseForge 的版本显示名常把项目名和游戏版本拼在一起（「Fabric API 0.92.3+1.20.1」、
 * 「JEI 15.2.0.27 for 1.20.1」）。取第一段完整版本号用于比较与展示，避免把项目名当成版本段。
 */
internal fun normalizedRemoteVersionNumber(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return text
    return remoteVersionToken.find(text)?.groupValues?.getOrNull(1) ?: text
}

/** 语义化版本比较：数值段按整数比较（1.2 < 1.10），预发布后缀低于正式版，构建元数据忽略。 */
internal fun compareModVersions(
    left: String,
    right: String,
): Int {
    val a = parseVersion(left)
    val b = parseVersion(right)
    val core = compareCore(a.core, b.core)
    if (core != 0) return core
    if (a.pre.isEmpty() && b.pre.isEmpty()) return 0
    if (a.pre.isEmpty()) return 1
    if (b.pre.isEmpty()) return -1
    return comparePre(a.pre, b.pre)
}

internal fun isPrereleaseVersion(value: String): Boolean {
    val core = value.trim().substringBefore('+')
    return core.contains('-') && core.substringAfter('-').isNotBlank()
}

/** 常见 Minecraft 版本约束：精确版本、`1.20.x`、`>=1.20 <1.21`、Maven 区间 `[1.20,1.21)`。 */
internal fun minecraftConstraintMatches(
    constraint: String,
    version: String,
): Boolean {
    val text = constraint.trim()
    if (text.isEmpty() || text == "*") return true
    if (text.contains("||")) return text.split("||").any { minecraftConstraintMatches(it, version) }
    if ((text.startsWith("[") || text.startsWith("(")) && text.length > 2) {
        val close = text.indexOfAny(charArrayOf(']', ')'))
        if (close > 0) {
            val parts = text.substring(1, close).split(',')
            if (parts.size == 1) {
                return parts.first().trim().let { it.isEmpty() || sameVersion(version, it) }
            }
            val lower = parts.first().trim()
            val upper = parts.getOrNull(1).orEmpty().trim()
            if (lower.isNotEmpty()) {
                val comparison = compareModVersions(version, lower)
                if (comparison < 0 || (comparison == 0 && text.startsWith("("))) return false
            }
            if (upper.isNotEmpty()) {
                val comparison = compareModVersions(version, upper)
                if (comparison > 0 || (comparison == 0 && text[close] == ')')) return false
            }
            return true
        }
    }
    val clauses = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    if (clauses.isEmpty()) return true
    return clauses.all { clause ->
        when {
            clause.startsWith(">=") -> compareModVersions(version, clause.drop(2)) >= 0
            clause.startsWith("<=") -> compareModVersions(version, clause.drop(2)) <= 0
            clause.startsWith(">") -> compareModVersions(version, clause.drop(1)) > 0
            clause.startsWith("<") -> compareModVersions(version, clause.drop(1)) < 0
            clause.startsWith("=") -> matchesVersionPrefix(version, clause.drop(1))
            clause.startsWith("~") || clause.startsWith("^") ->
                matchesVersionPrefix(version, clause.drop(1))
            else -> matchesVersionPrefix(version, clause)
        }
    }
}

private fun matchesVersionPrefix(
    version: String,
    expected: String,
): Boolean {
    val raw = expected.trim()
    if (raw.isEmpty() || raw == "*") return true
    val prefix =
        raw
            .removeSuffix(".*")
            .removeSuffix(".x")
            .removeSuffix(".X")
            .removeSuffix("+")
            .trim()
    if (prefix.isEmpty()) return true
    val value = version.trim()
    return sameVersion(value, prefix) || value.startsWith("$prefix.")
}

private fun sameVersion(
    left: String,
    right: String,
) = compareModVersions(left, right) == 0

private data class ParsedVersion(
    val core: List<String>,
    val pre: List<String>,
)

private fun parseVersion(raw: String): ParsedVersion {
    var text =
        raw
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
    if (text.isEmpty()) return ParsedVersion(emptyList(), emptyList())
    val dash = text.indexOf('-')
    val coreText = if (dash >= 0) text.substring(0, dash) else text
    val preText = if (dash >= 0) text.substring(dash + 1) else ""
    return ParsedVersion(
        coreText.split('.', '_').map { it.trim() }.filter { it.isNotEmpty() },
        preText.split('.', '-', '_').map { it.trim() }.filter { it.isNotEmpty() },
    )
}

private fun compareCore(
    left: List<String>,
    right: List<String>,
): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        val a = left.getOrNull(index) ?: "0"
        val b = right.getOrNull(index) ?: "0"
        val numericA = a.toLongOrNull()
        val numericB = b.toLongOrNull()
        val comparison =
            when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                numericA != null -> 1
                numericB != null -> -1
                else -> a.compareTo(b, ignoreCase = true)
            }
        if (comparison != 0) return comparison
    }
    return 0
}

private val prereleaseRank =
    mapOf(
        "snapshot" to 0,
        "dev" to 0,
        "alpha" to 1,
        "a" to 1,
        "beta" to 2,
        "b" to 2,
        "pre" to 3,
        "preview" to 3,
        "rc" to 4,
        "release" to 5,
    )

private fun comparePre(
    left: List<String>,
    right: List<String>,
): Int {
    for (index in 0 until maxOf(left.size, right.size)) {
        if (index >= left.size) return -1
        if (index >= right.size) return 1
        val a = left[index]
        val b = right[index]
        val numericA = a.toLongOrNull()
        val numericB = b.toLongOrNull()
        val comparison =
            when {
                numericA != null && numericB != null -> numericA.compareTo(numericB)
                // 语义化规则：数字标识低于字母标识（1.0.0-alpha < 1.0.0-1）。
                numericA != null -> -1
                numericB != null -> 1
                else -> {
                    val rankA = prereleaseRank[a.lowercase()]
                    val rankB = prereleaseRank[b.lowercase()]
                    when {
                        rankA != null && rankB != null -> rankA.compareTo(rankB)
                        rankA != null -> -1
                        rankB != null -> 1
                        else -> a.compareTo(b, ignoreCase = true)
                    }
                }
            }
        if (comparison != 0) return comparison
    }
    return 0
}

private fun normalizeName(value: String) = value.lowercase().filter { it.isLetterOrDigit() }

internal fun nameMatches(
    query: String,
    candidate: String,
): Boolean {
    val left = normalizeName(query)
    val right = normalizeName(candidate)
    if (left.isEmpty() || right.isEmpty()) return false
    return left == right || (left.length >= 4 && (right.startsWith(left) || left.startsWith(right)))
}
