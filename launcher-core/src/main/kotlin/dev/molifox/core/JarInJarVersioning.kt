// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责嵌入依赖的版本约束入口：语法分派、版本排序、版本词边界与边界探测。

package dev.molifox.core

/** 只求值声明里的版本约束，不加载任何类，也不执行构建表达式。 */
internal object JarInJarVersioning {
    /**
     * [constraint] 是否接受 [version]。
     *
     * 语法特征优先于加载器声明：`[`/`(` 开头且不含谓词符号时按 Maven 区间，含谓词符号时按
     * SemVer；裸版本等无法区分的写法才回退到加载器方言。
     */
    fun satisfies(
        constraint: String,
        loader: JarInJarLoader,
        version: String,
    ): Boolean {
        if (constraint.isBlank()) return true
        if (version.isBlank()) return false
        val text = constraint.trim()
        val looksMaven = text.startsWith("[") || text.startsWith("(")
        val looksSemver = text.any { it in "><~^*" } || text.contains("||")
        return when {
            looksMaven && !looksSemver -> JarInJarConstraintSyntax.maven(text, version)
            looksSemver && !looksMaven -> JarInJarConstraintSyntax.semver(text, version)
            loader.forgeLike -> JarInJarConstraintSyntax.maven(text, version)
            // 未知方言先按 SemVer，再按 Maven 兜底；两边都识别不了时按不满足处理。
            loader == JarInJarLoader.Unknown ->
                JarInJarConstraintSyntax.semver(text, version) ||
                    JarInJarConstraintSyntax.maven(text, version)
            else -> JarInJarConstraintSyntax.semver(text, version)
        }
    }

    /** 版本号排序；同一种比较口径贯穿选择与「当前版本」判定。 */
    fun compare(
        left: String,
        right: String,
    ): Int = compareModVersions(left, right)

    /** 去掉版本号的 `v` 前缀，`v1.20.1` 与 `1.20.1` 视为同一版本。 */
    fun stripVersionPrefix(raw: String): String {
        val text = raw.trim()
        return if (hasVersionPrefix(text)) text.substring(1) else text
    }

    private fun hasVersionPrefix(text: String): Boolean =
        text.length > 1 && (text[0] == 'v' || text[0] == 'V') && text[1].isDigit()

    /**
     * 约束是否至少有一个可比较的版本边界。
     *
     * 只有完全没有边界的畸形约束才允许 fail-open，否则 `(,2.0)` 这类只有上界的区间会被误判满足。
     */
    fun hasComparableBound(constraint: String): Boolean {
        if (constraint.isBlank()) return false
        return JarInJarConstraintSyntax.splitTopLevel(constraint).any { part ->
            val text = part.trim()
            when {
                text.isEmpty() -> false
                text[0] != '[' && text[0] != '(' ->
                    isComparableVersion(text.trimStart('>', '<', '=', '~', '^', ' ', '"'))
                else -> intervalHasBound(text)
            }
        }
    }

    /** 版本号是否作为完整版本词出现：`1.21` 不命中 `1.21.2`，也不命中 `9.1.20`。 */
    fun containsVersionToken(
        haystack: String,
        version: String,
    ): Boolean {
        if (haystack.isEmpty() || version.isEmpty()) return false
        var index = haystack.indexOf(version)
        while (index >= 0) {
            val before = if (index > 0) haystack[index - 1] else ' '
            val end = index + version.length
            val after = if (end < haystack.length) haystack[end] else ' '
            if (leadingTokenBoundary(before) && trailingTokenBoundary(haystack, end, after)) {
                return true
            }
            index = haystack.indexOf(version, index + 1)
        }
        return false
    }

    /** 版本号是否以数字开头；`latest`、`release` 这类标签不算可比较版本。 */
    fun isComparableVersion(raw: String): Boolean {
        var text = stripVersionPrefix(raw)
        if (text.endsWith("-")) text = text.dropLast(1)
        return text.isNotEmpty() && text[0].isDigit()
    }

    private fun leadingTokenBoundary(before: Char): Boolean = before != '.' && !before.isDigit()

    /** 版本词后面不能再接数字；接 `.` 时点号后也不能是数字（避免 `1.21` 命中 `1.21.2`）。 */
    private fun trailingTokenBoundary(
        haystack: String,
        end: Int,
        after: Char,
    ): Boolean =
        when {
            after.isDigit() -> false
            after == '.' -> {
                val next = if (end + 1 < haystack.length) haystack[end + 1] else ' '
                !next.isDigit()
            }
            else -> true
        }

    private fun intervalHasBound(text: String): Boolean {
        if (text.length < 2) return false
        val close = text.last()
        if (close != ']' && close != ')') return false
        val body = text.substring(1, text.length - 1)
        val comma = body.indexOf(',')
        if (comma < 0) return isComparableVersion(body.trim())
        return isComparableVersion(body.substring(0, comma).trim()) ||
            isComparableVersion(body.substring(comma + 1).trim())
    }
}
