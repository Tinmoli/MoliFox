// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 集中版本约束和版本排序规则，避免不同来源使用不同的比较方式。

package dev.molifox.core

private const val MIN_LOADER_PREFIX_CHARS = 4

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

/** 常见 Minecraft 版本约束：精确版本、`1.20.x`、`>=1.20 <1.21`、Maven 区间 `[1.20,1.21)`。 */
internal fun minecraftConstraintMatches(
    constraint: String,
    version: String,
): Boolean {
    val text = constraint.trim()
    if (text.isEmpty() || text == "*") return true
    if (text.contains("||")) {
        return text.split("||").any { minecraftConstraintMatches(it, version) }
    }
    closedRangeMatches(text, version)?.let { return it }
    val clauses = text.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
    return clauses.isEmpty() || clauses.all { clauseMatches(it, version) }
}

/** 单条版本条件：比较符在前时只比较版本号，其余按前缀匹配（`1.20.x`、`1.20.*`）。 */
private fun clauseMatches(
    clause: String,
    version: String,
): Boolean =
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

/** Maven 区间 `[1.20,1.21)` / `[1.20]`；不是区间写法时返回 null，交给空格分隔的条件判断。 */
private fun closedRangeMatches(
    text: String,
    version: String,
): Boolean? {
    if (!(text.startsWith("[") || text.startsWith("(")) || text.length <= 2) return null
    val close = text.indexOfAny(charArrayOf(']', ')'))
    if (close <= 0) return null
    val parts = text.substring(1, close).split(',')
    val lower = parts.first().trim()
    val upper = parts.getOrNull(1).orEmpty().trim()
    if (parts.size == 1) return lower.isEmpty() || sameVersion(version, lower)
    val lowerComparison = if (lower.isEmpty()) 1 else compareModVersions(version, lower)
    val upperComparison = if (upper.isEmpty()) -1 else compareModVersions(version, upper)
    val lowerOk = lowerComparison > 0 || (lowerComparison == 0 && !text.startsWith("("))
    val upperOk = upperComparison < 0 || (upperComparison == 0 && text[close] != ')')
    return lowerOk && upperOk
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
    val comparison =
        (0 until maxOf(left.size, right.size)).firstNotNullOfOrNull { index ->
            val a = left.getOrNull(index)
            val b = right.getOrNull(index)
            // 数字标识低于字母标识（1.0.0-alpha < 1.0.0-1），关键字按语义化顺序。
            val compared =
                if (a == null) {
                    -1
                } else if (b == null) {
                    1
                } else {
                    val numericA = a.toLongOrNull()
                    val numericB = b.toLongOrNull()
                    val rankA = prereleaseRank[a.lowercase()]
                    val rankB = prereleaseRank[b.lowercase()]
                    when {
                        numericA != null && numericB != null -> numericA.compareTo(numericB)
                        numericA != null -> -1
                        numericB != null -> 1
                        rankA != null && rankB != null -> rankA.compareTo(rankB)
                        rankA != null -> -1
                        rankB != null -> 1
                        else -> a.compareTo(b, ignoreCase = true)
                    }
                }
            compared.takeIf { it != 0 }
        }
    return comparison ?: 0
}

internal fun nameMatches(
    query: String,
    candidate: String,
): Boolean {
    val left = query.lowercase().filter { it.isLetterOrDigit() }
    val right = candidate.lowercase().filter { it.isLetterOrDigit() }
    if (left.isEmpty() || right.isEmpty()) return false
    if (left == right) return true
    val prefix = left.length >= MIN_LOADER_PREFIX_CHARS
    return prefix && (right.startsWith(left) || left.startsWith(right))
}
