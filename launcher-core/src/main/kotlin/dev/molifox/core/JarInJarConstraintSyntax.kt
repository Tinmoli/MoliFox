// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责版本约束的具体匹配算法：Maven 区间、SemVer 谓词、通配符与 ~/^ 展开。

package dev.molifox.core

private val whitespaceRun = Regex("\\s+")
private val digitRun = Regex("\\d+")

/** 拆出的操作符与版本体；`>=26.1-` 的预发布下限标记单独保留。 */
private data class VersionTerm(
    val operator: String,
    val value: String,
    val prereleaseFloor: Boolean,
)

/** 只做字符串与版本号比较；分派规则在 [JarInJarVersioning]。 */
internal object JarInJarConstraintSyntax {
    fun maven(
        constraint: String,
        version: String,
    ): Boolean =
        splitTopLevel(constraint).any { part ->
            val interval = part.trim()
            when {
                interval.isEmpty() -> false
                // Maven 的裸版本是软下限，不是精确匹配。
                interval[0] != '[' && interval[0] != '(' ->
                    JarInJarVersioning.compare(version, interval) >= 0
                else -> closedIntervalMatches(interval, version)
            }
        }

    private fun closedIntervalMatches(
        interval: String,
        version: String,
    ): Boolean {
        if (interval.length < 2) return false
        val close = interval.last()
        if (close != ']' && close != ')') return false
        val body = interval.substring(1, interval.length - 1)
        val comma = body.indexOf(',')
        if (comma < 0) {
            return body.trim().isNotEmpty() &&
                JarInJarVersioning.compare(version, body.trim()) == 0
        }
        val lower = body.substring(0, comma).trim()
        val upper = body.substring(comma + 1).trim()
        val lowerOk =
            lower.isEmpty() ||
                JarInJarVersioning.compare(version, lower).let {
                    if (interval[0] == '[') it >= 0 else it > 0
                }
        val upperOk =
            upper.isEmpty() ||
                JarInJarVersioning.compare(version, upper).let {
                    if (close == ']') it <= 0 else it < 0
                }
        return lowerOk && upperOk
    }

    fun semver(
        constraint: String,
        version: String,
    ): Boolean =
        constraint.split("||").any { alternative ->
            val terms = alternative.trim().split(whitespaceRun).filter { it.isNotBlank() }
            terms.isEmpty() || terms.all { termMatches(it, version) }
        }

    /** 按顶层逗号切分约束；Maven 区间内部的逗号不切开。 */
    fun splitTopLevel(constraint: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        constraint.forEachIndexed { index, character ->
            when (character) {
                '[', '(' -> depth++
                ']', ')' -> depth--
                ',' ->
                    if (depth == 0) {
                        parts += constraint.substring(start, index)
                        start = index + 1
                    }
                else -> Unit
            }
        }
        parts += constraint.substring(start)
        return parts
    }
}

private fun termMatches(
    term: String,
    version: String,
): Boolean {
    if (term == "*" || term.equals("any", true)) return true
    val parsed = parseTerm(term)
    return when {
        parsed.value.isEmpty() -> false
        parsed.operator.isEmpty() && (parsed.value[0] == '~' || parsed.value[0] == '^') ->
            tildeOrCaretMatches(parsed.value, version)
        parsed.operator.isEmpty() && hasWildcardSuffix(parsed.value) ->
            wildcardMatches(parsed.value.dropLast(2), version)
        !JarInJarVersioning.isComparableVersion(parsed.value) ->
            (parsed.operator.isEmpty() || parsed.operator == "=") &&
                version.equals(parsed.value, true)
        prereleaseFloorMatches(parsed, version) -> true
        else ->
            comparisonMatches(
                parsed.operator,
                JarInJarVersioning.compare(version, parsed.value),
            )
    }
}

private fun parseTerm(term: String): VersionTerm {
    var index = 0
    while (index < term.length && term[index] in "><=") {
        index++
    }
    val operator = term.substring(0, index)
    var value = term.substring(index).trim()
    // Fabric 预发布下限写法 `>=26.1-`：26.1 的预发布也计入下界。
    val prereleaseFloor = value.endsWith('-')
    if (prereleaseFloor) value = value.dropLast(1)
    return VersionTerm(operator, value, prereleaseFloor)
}

private fun prereleaseFloorMatches(
    term: VersionTerm,
    version: String,
): Boolean =
    term.prereleaseFloor &&
        term.operator in listOf("", ">=", ">") &&
        version.split('-').first().equals(term.value, true)

private fun comparisonMatches(
    operator: String,
    comparison: Int,
): Boolean =
    when (operator) {
        // Fabric/Quilt 的裸版本是精确匹配。
        "", "=", "==" -> comparison == 0
        ">=" -> comparison >= 0
        ">" -> comparison > 0
        "<=" -> comparison <= 0
        "<" -> comparison < 0
        else -> false
    }

private fun wildcardMatches(
    prefix: String,
    version: String,
): Boolean = prefix.isEmpty() || version == prefix || version.startsWith("$prefix.")

private fun hasWildcardSuffix(raw: String): Boolean = raw.endsWith(".x") || raw.endsWith(".X") || raw.endsWith(".*")

private fun tildeOrCaretMatches(
    raw: String,
    version: String,
): Boolean {
    val tilde = raw[0] == '~'
    val base = raw.substring(1).trim()
    if (!JarInJarVersioning.isComparableVersion(base)) return false
    if (JarInJarVersioning.compare(version, base) < 0) return false
    val numbers = digitRun.findAll(base).mapNotNull { it.value.toIntOrNull() }.toList()
    if (numbers.isEmpty()) return false
    val upper = if (tilde) tildeUpperBound(numbers) else caretUpperBound(numbers)
    return JarInJarVersioning.compare(version, upper) < 0
}

/** `~1.20` 允许 patch 级更新，`~1` 允许 minor 级更新。 */
private fun tildeUpperBound(numbers: List<Int>): String =
    if (numbers.size >= 2) {
        "${numbers[0]}.${numbers[1] + 1}"
    } else {
        "${numbers[0] + 1}"
    }

/** `^0.0.3` → 0.0.4，`^0.2` → 0.3，`^1.2.3` → 2；全部为 0 时只匹配自身。 */
private fun caretUpperBound(numbers: List<Int>): String {
    var index = 0
    while (index < numbers.size && numbers[index] == 0) index++
    if (index >= numbers.size) index = numbers.size - 1
    val upper = numbers.take(index) + listOf(numbers[index] + 1)
    return upper.joinToString(".")
}
